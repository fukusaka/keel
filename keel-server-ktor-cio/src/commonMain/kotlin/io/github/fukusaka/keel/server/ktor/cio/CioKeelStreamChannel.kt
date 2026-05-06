package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.ktor.utils.io.BufferedByteWriteChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.Sink
import kotlinx.io.readByteArray
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Ktor [BufferedByteWriteChannel] backed directly by a keel [PipelinedChannel],
 * emitting chunked-encoded frames without routing bytes through the
 * `output` [io.ktor.utils.io.ByteWriteChannel] / [pumpOutputToChannel] path.
 *
 * The CIO pattern uses [io.ktor.utils.io.ByteChannel] + a pump coroutine
 * ([KtorCioConnectionHandler.pumpOutputToChannel]) that reads from an
 * intermediate `output` ByteChannel and calls [PipelinedChannel.pipeline]
 * `requestWrite + requestFlush` per `readAvailable` call.  Because
 * [pumpOutputToChannel] is pinned to the EventLoop thread, each SSE frame
 * requires a separate EL wake-up cycle — one eventfd write + one SQE/CQE
 * round-trip for io_uring — collapsing throughput to ≈ 28 RPS for io_uring
 * (epoll: ≈ 154 RPS) against the ktor-cio reference of ≈ 1 266 RPS.
 *
 * This class eliminates the intermediate ByteChannel entirely.  Each user
 * [flush] fire-and-forgets one `emitChunk` task to the EventLoop, so all
 * 100 SSE frames can be enqueued before the EventLoop picks them up,
 * enabling the engine to batch SQE submissions in a single io_uring ring
 * iteration rather than serialising one per wake-up.
 *
 * The class is used ONLY for the chunked streaming path
 * ([responseChannel] with no `Content-Length`).  Fixed-length and
 * non-streaming responses continue to use the `output` ByteChannel.
 *
 * **HTTP response framing**: this class is responsible for the chunked
 * body only.  Headers are written to `output` by [KeelCioApplicationResponse]
 * before this channel is returned to the caller; the EventLoop's FIFO task
 * queue ensures headers arrive at the transport before any body chunk
 * dispatched by this channel.
 *
 * **Dispatch model**: identical to
 * [io.github.fukusaka.keel.server.ktor.KeelByteWriteChannel] — each
 * [flush] fire-and-forgets a raw [Runnable] to [PipelinedChannel.ioDispatcher].
 * The terminal `0\r\n\r\n` is written by [terminate] via `withContext`, so it
 * is enqueued after all preceding body tasks and [awaitFlushComplete] blocks
 * until the last Netty/epoll flush future completes.
 *
 * **Chunked encoding**: each chunk is encoded inline as one contiguous
 * [io.github.fukusaka.keel.buf.IoBuf] allocation:
 * `{hex-size}\r\n{data}\r\n`. No separate [io.github.fukusaka.keel.codec.http.HttpBody]
 * wrapper is needed — the CIO pipeline carries no HTTP codec, so raw
 * [io.github.fukusaka.keel.buf.IoBuf] is written directly to the transport.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CioKeelStreamChannel(
    private val pipelinedChannel: PipelinedChannel,
    private val scope: CoroutineScope,
) : BufferedByteWriteChannel {

    private val internalBuffer: Buffer = Buffer()
    private val closed = AtomicBoolean(false)
    private val terminated = AtomicBoolean(false)
    private val closeCause = AtomicReference<Throwable?>(null)

    override val autoFlush: Boolean = false

    override val isClosedForWrite: Boolean get() = closed.load()

    override val closedCause: Throwable? get() = closeCause.load()?.let { wrapClosedCause(it) }

    @InternalAPI
    override val writeBuffer: Sink = internalBuffer

    override suspend fun flush() {
        val cause = closeCause.load()
        if (cause != null) throw wrapClosedCause(cause)
        if (closed.load()) return
        drainAndDispatch()
    }

    @InternalAPI
    override fun flushWriteBuffer() {
        if (closed.load()) return
        if (internalBuffer.exhausted()) return
        val bytes = internalBuffer.readByteArray()
        scope.launch(pipelinedChannel.ioDispatcher) {
            emitChunk(bytes)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        val remaining = if (internalBuffer.exhausted()) null else internalBuffer.readByteArray()
        scope.launch(pipelinedChannel.ioDispatcher) {
            if (remaining != null) emitChunk(remaining)
            terminate()
        }
    }

    override suspend fun flushAndClose() {
        val alreadyClosed = !closed.compareAndSet(expectedValue = false, newValue = true)
        // If cancel() was called (terminated is already true), skip body terminator.
        // Any other early close (emitChunk error) still needs terminate() to send
        // "0\r\n\r\n" so the client gets a well-formed chunked response.
        if (alreadyClosed && terminated.load()) return
        if (!alreadyClosed) drainAndDispatch()
        terminate()
    }

    override fun cancel(cause: Throwable?) {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        closeCause.store(cause)
        if (!internalBuffer.exhausted()) internalBuffer.readByteArray()
        terminated.store(true)
    }

    private fun drainAndDispatch() {
        if (internalBuffer.exhausted()) return
        val bytes = internalBuffer.readByteArray()
        pipelinedChannel.ioDispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext) {
            try {
                emitChunk(bytes)
            } catch (e: Throwable) {
                closeCause.compareAndSet(expectedValue = null, newValue = e)
                closed.store(true)
                if (e is Error) throw e
            }
        }
    }

    private fun emitChunk(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val hexSize = bytes.size.toString(HEX_RADIX)
        // Lay out: "{hex}\r\n{data}\r\n" as one contiguous IoBuf.
        val total = hexSize.length + CRLF_SIZE + bytes.size + CRLF_SIZE
        val ioBuf = pipelinedChannel.allocator.allocate(total)
        ioBuf.writeAscii(hexSize, 0, hexSize.length)
        ioBuf.writeByte(CR)
        ioBuf.writeByte(LF)
        ioBuf.writeByteArray(bytes, 0, bytes.size)
        ioBuf.writeByte(CR)
        ioBuf.writeByte(LF)
        pipelinedChannel.pipeline.requestWrite(ioBuf)
        pipelinedChannel.pipeline.requestFlush()
    }

    private suspend fun terminate() {
        if (!terminated.compareAndSet(expectedValue = false, newValue = true)) return
        withContext(pipelinedChannel.ioDispatcher) {
            val ioBuf = pipelinedChannel.allocator.allocate(CHUNKED_TRAILER.size)
            ioBuf.writeByteArray(CHUNKED_TRAILER, 0, CHUNKED_TRAILER.size)
            pipelinedChannel.pipeline.requestWrite(ioBuf)
            pipelinedChannel.pipeline.requestFlush()
            pipelinedChannel.awaitFlushComplete()
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun wrapClosedCause(cause: Throwable): Throwable = when (cause) {
        is kotlinx.coroutines.CancellationException ->
            kotlinx.coroutines.CancellationException(cause.message, cause)
        is kotlinx.coroutines.CopyableThrowable<*> ->
            cause.createCopy() ?: cause
        else -> io.ktor.utils.io.ClosedWriteChannelException(cause)
    }

    private companion object {
        private const val HEX_RADIX = 16
        private const val CRLF_SIZE = 2
        private val CR: Byte = '\r'.code.toByte()
        private val LF: Byte = '\n'.code.toByte()
        private val CHUNKED_TRAILER: ByteArray = "0\r\n\r\n".encodeToByteArray()
    }
}
