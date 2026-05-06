package io.github.fukusaka.keel.server.ktor

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
 * Abstract Ktor [BufferedByteWriteChannel] that routes writes directly to a keel
 * [PipelinedChannel], bypassing the intermediate `ByteChannel` + pump-coroutine
 * relay used by older connection handlers.
 *
 * This base class holds all of the lifecycle machinery shared by the codec-http
 * and ktor-http-cio streaming paths:
 * - [closed] / [terminated] / [closeCause] atomics for write-channel state
 * - [writeBuffer] backed by a non-thread-safe `kotlinx.io.Buffer` (single-writer
 *   contract inherited from [BufferedByteWriteChannel])
 * - [flush], [flushWriteBuffer], [close], [flushAndClose], [cancel] implementations
 * - [drainAndDispatch] — fire-and-forget dispatch of buffered bytes to the EventLoop
 * - [terminate] — idempotent call of [writeTerminator] + [PipelinedChannel.awaitFlushComplete]
 * - [wrapClosedCause] — mirrors Ktor's `CloseToken.wrapCause` policy
 *
 * **Dispatch model**: every drain forwards the buffered bytes to
 * [PipelinedChannel.ioDispatcher] via fire-and-forget
 * [kotlinx.coroutines.CoroutineDispatcher.dispatch] (not [withContext]). The
 * dispatch primitive is sufficient because [emit] only enqueues
 * `pipeline.requestWrite + requestFlush` — both async — so the caller never
 * needs to wait for completion. Ktor's
 * `BaseApplicationResponse.respondWriteChannelContent` wraps the user `writeTo`
 * lambda in `withContext(Dispatchers.IOBridge)`, so [flush] is called from
 * `Dispatchers.IO` and always crosses threads to reach the EL; there is no
 * fast path to take.
 *
 * **Subclass responsibilities**:
 * - [emit] — called on the EL thread; encodes and writes one chunk of body bytes
 *   to the pipeline. The codec-http path wraps bytes in an `HttpBody` message;
 *   the ktor-http-cio path encodes `{hex}\r\n{data}\r\n` as a raw
 *   [io.github.fukusaka.keel.buf.IoBuf].
 * - [writeTerminator] — called inside [withContext]\([PipelinedChannel.ioDispatcher]\)
 *   by [terminate]; writes the end-of-body marker and requests the final flush.
 *   The codec-http path sends an `HttpBodyEnd` message; the ktor-http-cio path
 *   writes the `0\r\n\r\n` chunked trailer.
 *
 * **Error handling**: [emit] runs as a raw `Runnable` on the EL, outside any
 * coroutine. An uncaught exception would propagate up the EL's `drainTasks` loop
 * and kill the daemon. [drainAndDispatch] therefore catches and stores the cause
 * in [closeCause]; subsequent [flush] / [flushAndClose] calls rethrow it to the
 * caller.
 */
@OptIn(ExperimentalAtomicApi::class)
internal abstract class AbstractPipelinedWriteChannel(
    protected val pipelinedChannel: PipelinedChannel,
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
        // Snapshot bytes synchronously so the user buffer is reset before the
        // launched coroutine runs (the user thread may immediately start the
        // next write).
        val bytes = internalBuffer.readByteArray()
        scope.launch(pipelinedChannel.ioDispatcher) {
            emit(bytes)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        // Snapshot remaining bytes (if any) on the calling thread.
        val remaining = if (internalBuffer.exhausted()) null else internalBuffer.readByteArray()
        scope.launch(pipelinedChannel.ioDispatcher) {
            if (remaining != null) emit(remaining)
            terminate()
        }
    }

    override suspend fun flushAndClose() {
        val alreadyClosed = !closed.compareAndSet(expectedValue = false, newValue = true)
        // If cancel() was called (terminated is already true), skip: no body terminator
        // should be sent for a cancelled/abandoned response. Any other early close (e.g.
        // emit() error setting closed=true) still needs terminate() so the terminator
        // reaches the wire and the client gets a well-formed response.
        if (alreadyClosed && terminated.load()) return
        if (!alreadyClosed) drainAndDispatch()
        terminate()
    }

    override fun cancel(cause: Throwable?) {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        closeCause.store(cause)
        // Discard any buffered bytes — the connection is going away.
        if (!internalBuffer.exhausted()) internalBuffer.readByteArray()
        terminated.store(true)
    }

    /**
     * Encodes and writes [bytes] to the pipeline as one body chunk.
     * Caller MUST be on [PipelinedChannel.ioDispatcher].
     */
    protected abstract fun emit(bytes: ByteArray)

    /**
     * Writes the trailing end-of-body frame and requests the final flush.
     * Called inside [withContext]\([PipelinedChannel.ioDispatcher]\) by [terminate];
     * [terminate] calls [PipelinedChannel.awaitFlushComplete] after this returns.
     */
    protected abstract fun writeTerminator()

    /**
     * Drains [internalBuffer] and dispatches one [emit] call fire-and-forget to
     * [PipelinedChannel.ioDispatcher].
     *
     * The EventLoop's FIFO task queue keeps body chunks ahead of the trailing
     * terminator that [terminate] enqueues with a real suspend, so ordering is
     * preserved without explicit synchronisation. Errors are caught and surfaced
     * via [closeCause] so they reach the caller on the next [flush] /
     * [flushAndClose] call.
     */
    private fun drainAndDispatch() {
        if (internalBuffer.exhausted()) return
        val bytes = internalBuffer.readByteArray()
        pipelinedChannel.ioDispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext) {
            try {
                emit(bytes)
            } catch (e: Throwable) {
                closeCause.compareAndSet(expectedValue = null, newValue = e)
                closed.store(true)
                if (e is Error) throw e
            }
        }
    }

    /**
     * Sends the body terminator and awaits the final flush so the connection
     * handler cannot reuse the socket while bytes are still in flight on
     * push-mode engines. Idempotent.
     */
    private suspend fun terminate() {
        if (!terminated.compareAndSet(expectedValue = false, newValue = true)) return
        withContext(pipelinedChannel.ioDispatcher) {
            writeTerminator()
            pipelinedChannel.awaitFlushComplete()
        }
    }

    /**
     * Wraps the recorded close cause into a fresh [Throwable] for each
     * user-visible throw, mirroring Ktor's `CloseToken.wrapCause` pattern.
     * Throwing the same instance repeatedly mutates its stack trace and lets
     * `addSuppressed` accumulate across catch sites; wrapping keeps each
     * surfaced exception independent.
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    protected fun wrapClosedCause(cause: Throwable): Throwable = when (cause) {
        is kotlinx.coroutines.CancellationException ->
            kotlinx.coroutines.CancellationException(cause.message, cause)
        is kotlinx.coroutines.CopyableThrowable<*> ->
            cause.createCopy() ?: cause
        else -> io.ktor.utils.io.ClosedWriteChannelException(cause)
    }
}
