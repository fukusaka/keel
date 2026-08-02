package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io_uring.io_uring
import io_uring.io_uring_queue_exit
import io_uring.io_uring_queue_init
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.runBlocking
import platform.posix.close
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
        val engine = IoUringEngine(config = testConfig(), capabilities = caps)
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
    fun `bindPipeline transports honour the engine writeModeSelector`() {
        // Regression test: the pipelined server previously constructed its
        // transports without forwarding the engine's writeModeSelector, so
        // every bindPipeline connection silently wrote in the transport's
        // default FALLBACK_CQE mode regardless of the engine configuration
        // (the Coroutine-mode bind/connect paths did forward it). Pin the
        // contract through the SEND_ZC dispatch counters: with the SEND_ZC
        // selector, an echoed response must produce at least one zero-copy
        // dispatch — zero means the selector never reached the transport.
        val caps = detectCaps()
        if (!caps.sendZc) return
        val engine = IoUringEngine(
            config = testConfig(),
            writeModeSelector = IoModeSelectors.SEND_ZC,
            capabilities = caps,
        )
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = rawConnect(port)
        try {
            rawWrite(clientFd, "hello")
            assertEquals("hello", rawRead(clientFd, 5))
        } finally {
            close(clientFd)
            server.close()
            runBlocking { engine.close() }
        }
        // The per-EL counters are EL-confined; reading them is safe after
        // engine.close() has joined the EventLoop pthreads.
        assertTrue(
            engine.totalSendZcDispatchCount() > 0,
            "writeModeSelector = SEND_ZC must reach bindPipeline transports " +
                "(0 zero-copy dispatches means the engine selector was ignored)",
        )
    }

    @Test
    fun `SEND_ZC delivers a small multi-buffer flush without truncation or leak`() {
        // Minimal reproduction of the chain-drop bug: two tiny buffers in one
        // flush. Exercises submitAsyncSendZcChain advancing past index 0.
        runMultiBufferSendZcResponse(bodySize = 32)
    }

    @Test
    fun `SEND_ZC delivers a large multi-buffer response without truncation or leak`() {
        // The real /large shape: a small header buffer + a 100 KB body buffer
        // in one flush. Adds coverage the tiny-buffer case cannot: the 100 KB
        // send is split across CQEs (partial send → recursive resubmit inside
        // submitAsyncSendZcSequential), so this pins both the multi-buffer
        // chain and the partial-send remainder path end to end.
        runMultiBufferSendZcResponse(bodySize = 100_000)
    }

    /**
     * Drives a two-buffer (header + [bodySize]-byte body) response through the
     * pipelined io_uring server under [IoMode.SEND_ZC] and asserts the client
     * receives every byte and no buffer leaks.
     *
     * Regression: submitAsyncSendZcChain indexed the LIVE pendingWrites list,
     * but flush() clears pendingWrites the moment flushSendZc() returns. Only
     * the first buffer (read synchronously before the clear) was sent; every
     * later buffer in the async chain saw an empty list, was silently dropped,
     * and its IoBuf leaked. A multi-buffer response (HTTP header + body — the
     * /large shape) therefore delivered only its header and the peer stalled
     * until timeout. Pins both full delivery and zero leak.
     */
    private fun runMultiBufferSendZcResponse(bodySize: Int) {
        val caps = detectCaps()
        if (!caps.sendZc) return
        // Track buffers allocated through the channel allocator so a dropped
        // (never-released) buffer surfaces as a non-zero outstanding count.
        val tracking = TrackingAllocator(DefaultAllocator)
        val engine = IoUringEngine(
            config = IoEngineConfig(loggerFactory = PrintLogger.Factory(LogLevel.DEBUG), allocator = tracking),
            writeModeSelector = IoModeSelectors.SEND_ZC,
            capabilities = caps,
        )
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("two", TwoBufferResponseHandler(bodySize))
        }
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = rawConnect(port)
        try {
            rawWrite(clientFd, "go")
            val header = TwoBufferResponseHandler.HEADER
            val total = header.length + bodySize
            // rawReadBytes returns a short array on the deadline instead of
            // hanging, so the pre-fix path (dropped body buffer) fails the
            // length assertion rather than blocking the test forever.
            val got = PosixRawClient.rawReadBytes(clientFd, total, 10.seconds)
            assertEquals(
                total,
                got.size,
                "SEND_ZC must deliver every buffer batched into one flush " +
                    "(a short read means the async send chain dropped a later buffer)",
            )
            assertEquals(
                header,
                got.decodeToString(0, header.length),
                "the header buffer must arrive intact and first",
            )
            assertEquals(bodyByteAt(0), got[header.length], "first body byte")
            assertEquals(bodyByteAt(bodySize - 1), got[total - 1], "last body byte")
        } finally {
            close(clientFd)
            server.close()
            runBlocking { engine.close() }
        }
        // engine.close() joins the EventLoop pthreads, so every send
        // completion has fired and released its buffer by now; a buffer the
        // chain dropped would still be outstanding.
        assertEquals(
            0,
            tracking.outstandingCount,
            "SEND_ZC must release every buffer of a multi-write flush (non-zero = leaked drop)",
        )
    }

    @Test
    fun `bindPipeline echoes with a custom SQ and CQ ring size`() {
        // Exercises the real kernel's IORING_SETUP_CQSIZE path (cqSize > 0) and
        // the startup IORING_FEAT_NODROP assert against a real ring — the seam
        // test covers the plumbing, this proves the kernel accepts the config.
        val engine = IoUringEngine(
            config = testConfig(),
            capabilities = detectCaps(),
            ringSize = 2048,
            cqSize = 8192,
        )
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = rawConnect(port)
        try {
            rawWrite(clientFd, "hello")
            assertEquals("hello", rawRead(clientFd, 5))
        } finally {
            close(clientFd)
            server.close()
            runBlocking { engine.close() }
        }
    }

    @Test
    fun `pipelined echo works with multishotRecv disabled - single-shot buffer-select fallback`() {
        // Exercises the single-shot recv fallback (the read mode used on a
        // kernel with a provided buffer ring but no IORING_RECV_MULTISHOT)
        // against the real kernel: the single-shot IORING_OP_RECV +
        // IOSQE_BUFFER_SELECT shape is valid on modern kernels too, so the
        // capability override makes the fallback path permanently testable
        // here without an actual 5.19 host. Two sequential round-trips pin
        // the per-CQE re-arm (the second echo only works if delivery
        // re-armed a fresh recv SQE).
        val caps = detectCaps().copy(multishotRecv = false)
        val engine = IoUringEngine(config = testConfig(), capabilities = caps)
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = rawConnect(port)
        try {
            rawWrite(clientFd, "hello")
            assertEquals("hello", rawRead(clientFd, 5))
            rawWrite(clientFd, "world")
            assertEquals("world", rawRead(clientFd, 5), "second echo requires the per-CQE re-arm")
        } finally {
            close(clientFd)
            server.close()
            runBlocking { engine.close() }
        }
    }

    @Test
    fun `pipelined echo works without a provided buffer ring - allocator recv fallback`() {
        // Exercises the allocator-buffer recv fallback (the read mode used
        // on a kernel without IORING_REGISTER_PBUF_RING) against the real
        // kernel: plain single-shot IORING_OP_RECV into a caller-owned
        // buffer is valid on modern kernels too, so the capability override
        // makes the pre-ring tier permanently testable here. The matrix
        // matches a real pre-5.19 kernel (no ring implies no multishot
        // recv). Two sequential round-trips pin the per-CQE re-arm with a
        // fresh allocation per recv.
        val caps = detectCaps().copy(providedBufferRing = false, multishotRecv = false)
        val engine = IoUringEngine(config = testConfig(), capabilities = caps)
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = rawConnect(port)
        try {
            rawWrite(clientFd, "hello")
            assertEquals("hello", rawRead(clientFd, 5))
            rawWrite(clientFd, "world")
            assertEquals("world", rawRead(clientFd, 5), "second echo requires the per-CQE re-arm")
        } finally {
            close(clientFd)
            server.close()
            runBlocking { engine.close() }
        }
    }

    @Test
    fun `invalid capability cell - multishot recv without a ring - is normalized and still echoes`() {
        // multishot recv requires kernel-side buffer selection, so the cell
        // (multishotRecv = true, providedBufferRing = false) cannot occur
        // from version detection — only a manual override can produce it.
        // The engine normalizes it (warn + effective multishotRecv = false)
        // instead of letting the kernel reject every recv with -EINVAL.
        val caps = detectCaps().copy(providedBufferRing = false)
        if (!caps.multishotRecv) return // kernel too old to form the invalid cell
        val engine = IoUringEngine(config = testConfig(), capabilities = caps)
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = rawConnect(port)
        try {
            rawWrite(clientFd, "hello")
            assertEquals("hello", rawRead(clientFd, 5))
        } finally {
            close(clientFd)
            server.close()
            runBlocking { engine.close() }
        }
    }

    @Test
    fun `pipelined echo works with multishotAccept disabled - single-shot accept fallback`() {
        // Exercises the single-shot accept fallback (the accept mode used on
        // a kernel without IORING_ACCEPT_MULTISHOT, < 5.19) against the real
        // kernel: plain IORING_OP_ACCEPT is valid on modern kernels too, so
        // the capability override keeps the fallback permanently testable.
        // Two SEQUENTIAL CONNECTIONS pin the per-CQE re-arm — the second
        // connection is only accepted if the first CQE re-armed a fresh
        // accept SQE.
        val caps = detectCaps().copy(multishotAccept = false)
        val engine = IoUringEngine(config = testConfig(), capabilities = caps)
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        try {
            repeat(2) { i ->
                val clientFd = rawConnect(port)
                try {
                    rawWrite(clientFd, "msg-$i")
                    assertEquals(
                        "msg-$i",
                        rawRead(clientFd, 5),
                        "connection ${i + 1} requires the per-CQE accept re-arm",
                    )
                } finally {
                    close(clientFd)
                }
            }
        } finally {
            server.close()
            runBlocking { engine.close() }
        }
    }

    @Test
    fun `pipelined echo works with the full pre-5_19 capability profile`() {
        // End-to-end smoke of the oldest supported tier (a 5.6-era kernel):
        // single-shot accept + allocator-buffer single-shot recv + no
        // zero-copy send + none of the 5.18+/6.0+ setup flags. Every
        // individual fallback has its own test above; this pins that the
        // tiers compose.
        val caps = detectCaps().copy(
            multishotAccept = false,
            multishotRecv = false,
            providedBufferRing = false,
            sendZc = false,
            sendmsgZc = false,
            coopTaskrun = false,
            singleIssuer = false,
            registerRingFd = false,
        )
        val engine = IoUringEngine(config = testConfig(), capabilities = caps)
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        try {
            repeat(2) { i ->
                val clientFd = rawConnect(port)
                try {
                    rawWrite(clientFd, "msg-$i")
                    assertEquals("msg-$i", rawRead(clientFd, 5))
                } finally {
                    close(clientFd)
                }
            }
        } finally {
            server.close()
            runBlocking { engine.close() }
        }
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
        val engine = IoUringEngine(config = testConfig(), capabilities = caps)
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
        val engine = IoUringEngine(config = testConfig(), capabilities = caps)
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
        val engine = IoUringEngine(config = testConfig(), capabilities = caps)
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

    @Test
    fun `a handler that retains every recv buffer does not stall the loop's receives`() {
        // Regression for the cross-connection ring-pinning stall: a consumer
        // that retains delivered ring buffers (the codec does, for header
        // views) used to drain a small ring after `slotCount` messages — the
        // recv died with -ENOBUFS and the deferred re-arm never fired
        // because the pinned buffers were never returned. Copy-on-pressure
        // bounds the pinning: once the ring is low, deliveries are
        // allocator-owned copies and the slots return immediately, so all
        // messages keep flowing. With a 4-slot ring, the pre-fix server
        // stalls after at most 4 messages; 8 sent + echo of the last one
        // proves the receive path outlived the pinning.
        val retained = ArrayList<IoBuf>()
        val engine = IoUringEngine(
            config = testConfig(),
            capabilities = detectCaps(),
            bufferRingSlotCount = 4,
        )
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast(
                "retainer",
                object : InboundHandler {
                    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                        if (msg !is IoBuf) return ctx.propagateRead(msg)
                        if (retained.size < RETAINED_MESSAGES) {
                            // Pin the delivered buffer like a codec holding
                            // header views: keep the reference, no release.
                            retained.add(msg)
                        } else {
                            // Final message: release every pinned buffer (on
                            // the EventLoop thread, as the codec would) and
                            // echo it so the client observes liveness.
                            for (buf in retained) buf.release()
                            retained.clear()
                            ctx.propagateWrite(msg)
                            ctx.propagateFlush()
                        }
                    }
                },
            )
        }
        val port = (server.localAddress as InetSocketAddress).port
        val clientFd = rawConnect(port)
        try {
            // Distinct sends with a small gap so each arrives as its own
            // recv delivery (TCP coalescing would under-count the pins and
            // weaken the scenario, not break it).
            repeat(RETAINED_MESSAGES) {
                rawWrite(clientFd, "pinme")
                platform.posix.usleep(50_000u)
            }
            rawWrite(clientFd, "final")
            val received = rawRead(clientFd, 5)
            assertEquals(
                "final",
                received,
                "receives must outlive $RETAINED_MESSAGES pinned deliveries on a 4-slot ring",
            )
        } finally {
            close(clientFd)
            server.close()
            runBlocking { engine.close() }
        }
    }

    @Test
    fun `pipelined echo works with a non-default buffer ring size`() {
        // Config plumbing: a power-of-two override reaches the per-EventLoop
        // rings (a non-power-of-two would fail ring construction).
        val engine = IoUringEngine(config = testConfig(), capabilities = detectCaps(), bufferRingSlotCount = 8)
        val server = engine.bindPipeline("127.0.0.1", 0, BindConfig()) { channel ->
            channel.pipeline.addLast("echo", EchoHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port
        val clientFd = rawConnect(port)
        try {
            rawWrite(clientFd, "hello")
            assertEquals("hello", rawRead(clientFd, 5))
        } finally {
            close(clientFd)
            server.close()
            runBlocking { engine.close() }
        }
    }

    // --- Helpers ---

    /**
     * [IoEngineConfig] wired to [PrintLogger] at DEBUG so CQE callback
     * exception warnings (added in PR #318) and direct-alloc slot traces
     * (PR #317) appear in CI test output — `NoopLoggerFactory` (default)
     * would silently drop them and the `io_uring stress` workflow's
     * JUnit XML / HTML reports would surface only the `read -1` symptom
     * without any server-side context.
     */
    private fun testConfig(): IoEngineConfig =
        IoEngineConfig(loggerFactory = PrintLogger.Factory(LogLevel.DEBUG))

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

    // Thin facades over shared PosixRawClient.
    // EINTR retry (PR #321's in-file workaround) now lives at Layer 1
    // (cinterop `keel_read` / `keel_write`). rawRead also enforces an
    // absolute monotonic deadline so signal storms cannot extend the
    // timeout via kernel timer reset.

    private fun rawConnect(port: Int): Int = PosixRawClient.rawConnect(port)

    private fun rawWrite(fd: Int, data: String): Unit = PosixRawClient.rawWrite(fd, data)

    private fun rawRead(fd: Int, size: Int): String = PosixRawClient.rawRead(fd, size)

    private companion object {
        /**
         * Pinned deliveries before the final, echoed message — double the
         * 4-slot ring so the pre-fix stall (at most 4 deliveries) is
         * unambiguous while keeping the inter-send pacing total around half
         * a second.
         */
        private const val RETAINED_MESSAGES = 8
    }
}

/**
 * Echoes each received [IoBuf] back down the pipeline.
 *
 * Ownership: `propagateWrite` transfers the inbound reference to the
 * transport (`IoTransport.write` takes over the caller's reference and
 * the flush path releases it) — the handler must NOT release after
 * writing. The previous body did call `msg.release()` here, a
 * double-release that went unnoticed because on the synchronous
 * FALLBACK_CQE flush path the resulting `IllegalStateException` was
 * thrown inside the recv-CQE callback, where the EventLoop's
 * catch-and-warn guard swallowed it after the echo bytes had already
 * left. The asynchronous SEND_ZC path surfaced it fatally: the handler's
 * release ran while the zero-copy send was still in flight, and the send
 * completion's own release then threw from an unguarded callback.
 */
private class EchoHandler : InboundHandler {
    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg is IoBuf) {
            ctx.propagateWrite(msg)
            ctx.propagateFlush()
        } else {
            ctx.propagateRead(msg)
        }
    }
}

/** Deterministic body byte pattern, shared by the writer and the assertion. */
private fun bodyByteAt(i: Int): Byte = (i % 251).toByte()

/**
 * On each request, writes a small header buffer and a [bodySize]-byte body
 * buffer, then a single flush — a faithful stand-in for an HTTP response's
 * separate header and body buffers (the /large shape) without pulling in the
 * HTTP codec.
 *
 * The two writes accumulate as two `PendingWrite` entries drained by one
 * `flush()`, exercising the multi-buffer async send chain that the SEND_ZC
 * live-list bug truncated. A large [bodySize] additionally forces a partial
 * send (kernel send buffer < body), exercising the chain's remainder path.
 */
private class TwoBufferResponseHandler(private val bodySize: Int) : InboundHandler {
    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg !is IoBuf) {
            ctx.propagateRead(msg)
            return
        }
        // Consume the request; this handler does not echo.
        msg.release()
        val header = HEADER.encodeToByteArray()
        val body = ByteArray(bodySize) { bodyByteAt(it) }
        val a = ctx.allocator.allocate(header.size).also { it.writeByteArray(header, 0, header.size) }
        val b = ctx.allocator.allocate(body.size).also { it.writeByteArray(body, 0, body.size) }
        ctx.propagateWrite(a)
        ctx.propagateWrite(b)
        ctx.propagateFlush()
    }

    companion object {
        const val HEADER = "HEADER-PART-0123456789"
    }
}
