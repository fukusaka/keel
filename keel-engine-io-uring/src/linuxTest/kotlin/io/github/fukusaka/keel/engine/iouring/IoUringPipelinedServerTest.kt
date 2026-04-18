package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.core.InetSocketAddress

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io_uring.io_uring
import io_uring.io_uring_queue_exit
import io_uring.io_uring_queue_init
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_RCVTIMEO
import platform.posix.close
import platform.posix.connect
import platform.posix.read
import platform.posix.setsockopt
import platform.posix.socket
import platform.posix.sockaddr_in
import platform.posix.timeval
import platform.posix.write
import posix_socket.keel_htons
import posix_socket.keel_loopback_addr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers the direct-allocated multishot accept path
 * ([IoUringCapabilities.acceptDirectAlloc]) by exercising the full
 * pipelined server flow with the capability flipped on and off.
 *
 * The test handler echoes received [IoBuf] payloads back to the
 * client; the assertion is byte-exact. The two capability settings
 * produce different code paths on the server side — accept SQE op,
 * FixedFileRegistry.claim vs register, shutdown path, close path —
 * but are expected to be end-to-end equivalent from the client's
 * view.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringPipelinedServerTest {

    // Runs an echo pipeline server with the given capabilities override,
    // connects a raw client, and asserts byte-for-byte echo.
    private fun runEchoTest(directAlloc: Boolean) {
        val caps = detectCaps().copy(acceptDirectAlloc = directAlloc)
        val engine = IoUringEngine(capabilities = caps)
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = rawConnect(port)
        try {
            rawWrite(clientFd, "hello")
            val received = rawRead(clientFd, 5)
            assertEquals("hello", received)
        } finally {
            close(clientFd)
            server.close()
            runBlocking { engine.close() }
        }
    }

    @Test
    fun `pipelined echo works with acceptDirectAlloc disabled`() {
        runEchoTest(directAlloc = false)
    }

    @Test
    fun `pipelined echo works with acceptDirectAlloc enabled`() {
        if (!kernelSupportsAcceptDirectAlloc()) return
        runEchoTest(directAlloc = true)
    }

    @Test
    fun `pipelined echo works with iowqMaxWorkers set`() {
        // Smoke test: set small IO_WQ limits and verify the engine still
        // runs the happy path. The limits don't affect keel's hot path
        // (multishot + SEND_ZC path does not use IO_WQ), so this just
        // exercises the register_iowq_max_workers syscall on EL init and
        // confirms no regression. Requires kernel 5.15+ for
        // IORING_REGISTER_IOWQ_MAX_WORKERS.
        if (!kernelSupportsIowqMaxWorkers()) return
        val caps = detectCaps().copy(
            iowqMaxBoundedWorkers = 4,
            iowqMaxUnboundedWorkers = 8,
        )
        val engine = IoUringEngine(capabilities = caps)
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = rawConnect(port)
        try {
            rawWrite(clientFd, "iowq")
            assertEquals("iowq", rawRead(clientFd, 4))
        } finally {
            close(clientFd)
            server.close()
            runBlocking { engine.close() }
        }
    }

    @Test
    fun `pipelined echo works with napiBusyPoll enabled`() {
        // Smoke test: verify the engine registers NAPI and runs the happy
        // path without crashing. Skip on kernels that don't support
        // IORING_REGISTER_NAPI (< 6.9) — the register call fails but the
        // engine should keep running on the slow path, so this test remains
        // a useful sanity check even there. We skip to avoid a log-noise
        // false positive.
        if (!kernelSupportsNapiBusyPoll()) return
        val caps = detectCaps().copy(napiBusyPoll = true, napiBusyPollTimeoutUs = 50)
        val engine = IoUringEngine(capabilities = caps)
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = rawConnect(port)
        try {
            rawWrite(clientFd, "napi")
            assertEquals("napi", rawRead(clientFd, 4))
        } finally {
            close(clientFd)
            server.close()
            runBlocking { engine.close() }
        }
    }

    @Test
    fun `multiple connections work with acceptDirectAlloc enabled`() {
        if (!kernelSupportsAcceptDirectAlloc()) return
        val caps = detectCaps().copy(acceptDirectAlloc = true)
        val engine = IoUringEngine(capabilities = caps)
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        try {
            // Serial connections exercise the FixedFileRegistry.claim
            // bookkeeping — each accept gets a fresh slot from the
            // kernel-allocated pool.
            repeat(5) { i ->
                val fd = rawConnect(port)
                try {
                    val msg = "msg-$i"
                    rawWrite(fd, msg)
                    assertEquals(msg, rawRead(fd, msg.length))
                } finally {
                    close(fd)
                }
            }
        } finally {
            server.close()
            runBlocking { engine.close() }
        }
    }

    // --- Helpers ---

    /**
     * Detect current kernel caps via a throwaway ring. We can't use the
     * engine's detected caps directly because we need to inject an override
     * before engine construction.
     */
    private fun detectCaps(): IoUringCapabilities = memScoped {
        val ring = alloc<io_uring>()
        val rc = io_uring_queue_init(8u, ring.ptr, 0u)
        check(rc == 0) { "io_uring_queue_init failed: $rc" }
        try {
            IoUringCapabilities.detect(ring.ptr)
        } finally {
            io_uring_queue_exit(ring.ptr)
        }
    }

    /** True if the kernel supports acceptDirectAlloc (Linux 5.19+). */
    private fun kernelSupportsAcceptDirectAlloc(): Boolean {
        val kv = KernelVersion.current()
        return kv >= KernelVersion(5, 19)
    }

    /** True if the kernel supports NAPI busy-poll registration (Linux 6.9+). */
    private fun kernelSupportsNapiBusyPoll(): Boolean {
        val kv = KernelVersion.current()
        return kv >= KernelVersion(6, 9)
    }

    /** True if the kernel supports IORING_REGISTER_IOWQ_MAX_WORKERS (Linux 5.15+). */
    private fun kernelSupportsIowqMaxWorkers(): Boolean {
        val kv = KernelVersion.current()
        return kv >= KernelVersion(5, 15)
    }

    private fun rawConnect(port: Int): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0)
        memScoped {
            val tv = alloc<timeval>()
            tv.tv_sec = 5
            tv.tv_usec = 0
            setsockopt(fd, SOL_SOCKET, SO_RCVTIMEO, tv.ptr, sizeOf<timeval>().convert())

            val addr = alloc<sockaddr_in>()
            addr.sin_family = AF_INET.convert()
            addr.sin_port = keel_htons(port.toUShort())
            addr.sin_addr.s_addr = keel_loopback_addr()
            val rc = connect(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert())
            check(rc == 0) { "connect failed: rc=$rc" }
        }
        return fd
    }

    private fun rawWrite(fd: Int, data: String) {
        data.encodeToByteArray().usePinned { pinned ->
            val n = write(fd, pinned.addressOf(0), data.length.convert())
            assertTrue(n > 0, "write failed: n=$n")
        }
    }

    private fun rawRead(fd: Int, size: Int): String {
        val buf = ByteArray(size)
        var total = 0
        while (total < size) {
            val n = buf.usePinned { pinned ->
                read(fd, pinned.addressOf(total), (size - total).convert())
            }
            if (n <= 0) break
            total += n.toInt()
        }
        return buf.decodeToString(0, total)
    }
}

/**
 * Echoes each received [IoBuf] back down the pipeline.
 *
 * Holds the buffer via [IoBuf.retain] across the write, releases
 * its original reference, and lets the pipeline (IoTransport.flush)
 * release the retained copy on completion.
 */
private class EchoHandler : InboundHandler {
    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg is IoBuf) {
            msg.retain()
            ctx.propagateWrite(msg)
            ctx.propagateFlush()
        } else {
            ctx.propagateRead(msg)
        }
    }
}
