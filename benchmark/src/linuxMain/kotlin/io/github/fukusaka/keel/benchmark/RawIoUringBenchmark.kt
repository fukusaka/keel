@file:OptIn(
    ExperimentalForeignApi::class,
    io.github.fukusaka.keel.core.InternalKeelApi::class,
)

package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.engine.iouring.IoUringEventLoop
import io.github.fukusaka.keel.engine.iouring.ProvidedBufferRing
import io.github.fukusaka.keel.engine.iouring.SocketUtils
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import io_uring.io_uring_prep_multishot_accept
import io_uring.io_uring_prep_send
import io_uring.keel_cqe_get_buf_id
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.pin
import platform.posix.EAGAIN
import platform.posix.ENOBUFS
import platform.posix.EWOULDBLOCK
import platform.posix.MSG_NOSIGNAL
import platform.posix.close
import platform.posix.errno
import platform.posix.send
import kotlin.coroutines.EmptyCoroutineContext

/**
 * Zero-suspend io_uring benchmark server — multi-thread EventLoopGroup.
 *
 * Bypasses Ktor and coroutines entirely: multishot accept and multishot recv
 * deliver events as callbacks on the EventLoop thread. HTTP parsing and
 * response writing are fully synchronous (no suspend points).
 *
 * **Multi-thread design**: one [IoUringEventLoop] per CPU core. Each loop owns
 * a private server socket created with `SO_REUSEPORT`: the kernel distributes
 * incoming connections across all sockets by hashing the connection 4-tuple.
 * This eliminates cross-thread accept coordination while saturating all cores.
 *
 * **EAGAIN fallback**: when direct send() blocks (kernel buffer full), the
 * remaining bytes are delivered via a SEND SQE so no response is ever dropped.
 *
 * **ENOBUFS re-arm**: when the provided buffer ring is temporarily empty, the
 * multishot recv is re-armed immediately.
 *
 * This measures the theoretical throughput ceiling of the io_uring engine
 * without coroutine continuation allocation or Ktor pipeline overhead.
 */
object RawIoUringBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val numThreads = maxOf(1, config.socket.threads ?: availableProcessors())
        val loops = Array(numThreads) { IoUringEventLoop(NoopLoggerFactory.logger("raw-io-uring-$it")) }
        // RECV_BUFFER_COUNT per EventLoop: each loop handles connections independently.
        val bufferRings = Array(numThreads) { i ->
            ProvidedBufferRing(loops[i].ringPtr, bufferCount = RECV_BUFFER_COUNT, bgid = 0)
        }
        // SO_REUSEPORT: N sockets bound to the same port, one per EventLoop.
        // The kernel distributes connections across them without coordination.
        val serverFds = IntArray(numThreads) {
            SocketUtils.createReusePortServerSocket("0.0.0.0", config.port)
        }

        loops.forEach { it.start() }

        for (i in loops.indices) {
            val loop = loops[i]
            val bufferRing = bufferRings[i]
            val serverFd = serverFds[i]
            loop.dispatch(EmptyCoroutineContext, kotlinx.coroutines.Runnable {
                loop.submitMultishot(
                    prepare = { sqe ->
                        io_uring_prep_multishot_accept(sqe, serverFd, null, null, 0)
                    },
                    onCqe = { res, _ ->
                        if (res >= 0) {
                            onAccept(res, loop, bufferRing)
                        }
                    },
                )
            })
        }

        return {
            serverFds.forEach { close(it) }
            bufferRings.forEach { it.close() }
            loops.forEach { it.close() }
        }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)

    /**
     * Number of provided recv buffers per EventLoop.
     * Must be a power of 2 and ≥ expected concurrent connections per thread.
     */
    private const val RECV_BUFFER_COUNT = 256
}

// Pre-encoded HTTP responses.
// Pinned once at module initialization so directSend() never calls pin()/unpin() per request.
private val HELLO_RESPONSE: ByteArray = buildResponse("Hello, World!")
private val LARGE_RESPONSE: ByteArray = buildResponse("x".repeat(LARGE_PAYLOAD_SIZE))
private val HELLO_PINNED: Pinned<ByteArray> = HELLO_RESPONSE.pin()
private val LARGE_PINNED: Pinned<ByteArray> = LARGE_RESPONSE.pin()

private fun buildResponse(body: String): ByteArray {
    val bytes = body.encodeToByteArray()
    return ("HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/plain\r\n" +
        "Content-Length: ${bytes.size}\r\n" +
        "Connection: keep-alive\r\n" +
        "\r\n").encodeToByteArray() + bytes
}

/**
 * Called on the EventLoop thread when a new client connection is accepted.
 * Creates [ConnectionState] and arms the initial multishot recv.
 */
@OptIn(ExperimentalForeignApi::class)
private fun onAccept(
    clientFd: Int,
    loop: IoUringEventLoop,
    bufferRing: ProvidedBufferRing,
) {
    SocketUtils.setNonBlocking(clientFd)
    ConnectionState(clientFd, loop, bufferRing).armRecv()
}

/**
 * Per-connection state. Handles HTTP request parsing and response writing
 * entirely on the EventLoop thread without suspension.
 *
 * **Multishot recv lifecycle**: [armRecv] submits a multishot recv SQE that
 * delivers one CQE per incoming data segment. If the provided buffer ring is
 * temporarily exhausted (-ENOBUFS), the recv is re-armed automatically.
 *
 * **Send EAGAIN fallback**: if direct send() blocks (kernel buffer full), the
 * remaining bytes are delivered via a SEND SQE ([scheduleAsyncSend]).
 */
@OptIn(ExperimentalForeignApi::class)
private class ConnectionState(
    private val fd: Int,
    private val loop: IoUringEventLoop,
    private val bufferRing: ProvidedBufferRing,
) {

    /**
     * Non-null while a SEND SQE is in flight.
     * Incoming data is dropped while async send is pending to avoid
     * overlapping SEND SQEs on the same fd (undefined behaviour in io_uring).
     */
    private var asyncSendPinned: Pinned<ByteArray>? = null

    /**
     * Submits a multishot recv SQE for this connection.
     *
     * Called once at connection creation and again if the buffer ring is
     * temporarily exhausted (-ENOBUFS terminates the current multishot).
     */
    fun armRecv() {
        loop.submitMultishotRecv(
            fd = fd,
            bgid = bufferRing.bgid,
            onCqe = { res, flags ->
                when {
                    res > 0 -> {
                        val bufId = keel_cqe_get_buf_id(flags).toInt()
                        onData(bufferRing.getPointer(bufId), res, bufId)
                    }
                    res == -ENOBUFS -> {
                        // Provided buffer ring temporarily empty: re-arm immediately.
                        // No buffer was consumed for this CQE, so no returnBuffer needed.
                        armRecv()
                    }
                    else -> {
                        // EOF (0) or unrecoverable error: close connection.
                        close(fd)
                    }
                }
            },
        )
    }

    /**
     * Called when data arrives on this connection.
     * Scans the received bytes for the endpoint path and calls [directSend] with
     * the appropriate pre-pinned response. No heap allocation on the hot path.
     */
    private fun onData(ptr: CPointer<ByteVar>, len: Int, bufId: Int) {
        // Return the buffer to the ring before the send path to keep the ring full.
        bufferRing.returnBuffer(bufId)

        if (asyncSendPinned != null) return // previous async send still in flight
        if (len < 14) return

        // Fast path: check "GET /" at offset 0 — no allocation, byte reads only.
        val b0 = ptr[0]; val b1 = ptr[1]; val b2 = ptr[2]; val b3 = ptr[3]; val b4 = ptr[4]
        if (b0 != 'G'.code.toByte() ||
            b1 != 'E'.code.toByte() ||
            b2 != 'T'.code.toByte() ||
            b3 != ' '.code.toByte() ||
            b4 != '/'.code.toByte()
        ) return

        // Check endpoint: "/hello" (b5='h') or "/large" (b5='l').
        when (ptr[5].toInt().toChar()) {
            'h' -> directSend(HELLO_PINNED, HELLO_RESPONSE.size)
            'l' -> directSend(LARGE_PINNED, LARGE_RESPONSE.size)
        }
    }

    /**
     * Sends the pre-pinned response via direct send() syscall.
     * For small responses (/hello, ~100 bytes), this typically completes in one syscall.
     * If the kernel send buffer is full (EAGAIN), delegates to [scheduleAsyncSend].
     *
     * [pinned] is a module-level pre-pinned value — never unpinned here.
     */
    private fun directSend(pinned: Pinned<ByteArray>, total: Int) {
        var written = 0
        while (written < total) {
            val n = send(fd, pinned.addressOf(written), (total - written).convert(), MSG_NOSIGNAL)
            if (n > 0) {
                written += n.toInt()
                continue
            }
            val err = errno
            if (err == EAGAIN || err == EWOULDBLOCK) {
                // Kernel send buffer full: schedule remaining bytes via SEND SQE.
                scheduleAsyncSend(pinned, written, total)
                return
            }
            break // unrecoverable error
        }
    }

    /**
     * Marks async send in-flight and submits a SEND SQE for bytes [startOffset]..[total).
     *
     * [pinned] is the module-level pre-pinned array; it is NOT unpinned on completion.
     * [asyncSendPinned] is set only to serve as an in-flight guard (non-null = busy).
     */
    private fun scheduleAsyncSend(pinned: Pinned<ByteArray>, startOffset: Int, total: Int) {
        asyncSendPinned = pinned // mark in-flight; value itself is unused (pre-pinned)
        submitSendSqe(pinned, startOffset, total)
    }

    /**
     * Submits a SEND SQE for bytes [offset]..[total) of [pinned].
     * On partial completion, recurses for the remainder.
     * [pinned] is a module-level pre-pinned value and is never unpinned here.
     */
    private fun submitSendSqe(pinned: Pinned<ByteArray>, offset: Int, total: Int) {
        val remaining = total - offset
        loop.submitMultishot(
            prepare = { sqe ->
                io_uring_prep_send(sqe, fd, pinned.addressOf(offset), remaining.convert(), MSG_NOSIGNAL)
            },
            onCqe = { res, _ ->
                val newOffset = if (res > 0) offset + res else offset
                val done = res <= 0 || newOffset >= total
                if (!done) {
                    submitSendSqe(pinned, newOffset, total)
                } else {
                    asyncSendPinned = null // clear in-flight guard
                    if (res <= 0) close(fd)
                }
            },
        )
    }
}
