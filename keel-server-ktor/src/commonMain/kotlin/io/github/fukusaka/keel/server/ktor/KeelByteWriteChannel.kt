package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.codec.http.HttpBody
import io.github.fukusaka.keel.codec.http.HttpBodyEnd
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
 * Ktor [BufferedByteWriteChannel] backed directly by a keel pipeline.
 *
 * Replaces the legacy [io.ktor.utils.io.ByteChannel] + bridge coroutine pattern
 * (see PR for K29) where user-side `flush()` calls were coalesced by the
 * intermediate `ByteChannel` `flushBuffer` + 8 KB `readAvailable` drain. Each
 * user [flush] here maps directly to one [PipelinedChannel] write+flush pair,
 * so a 100-frame SSE response now produces 100 distinct
 * `requestWrite(HttpBody) + requestFlush()` events instead of being grouped
 * into 1-15 events depending on coroutine scheduling.
 *
 * **Single-writer**: Ktor's [io.ktor.utils.io.ByteWriteChannel] contract
 * (single writer, no concurrent writes) lets us use a non-thread-safe
 * `kotlinx.io.Buffer` for [writeBuffer]. The `flush` / `close` /
 * `flushAndClose` paths read from the buffer on the same coroutine that
 * wrote to it.
 *
 * **Dispatch**: every drain forwards the chunk's [emitBody] to
 * [PipelinedChannel.ioDispatcher] with a fire-and-forget
 * [CoroutineDispatcher.dispatch], not [withContext]. The dispatch primitive
 * is sufficient because [emitBody] only enqueues
 * `pipeline.requestWrite + requestFlush` — both async, with bytes not on
 * the wire when they return — so the caller never needs to wait.
 * Ktor's `BaseApplicationResponse.respondWriteChannelContent` always wraps
 * the user `writeTo` lambda in `withContext(Dispatchers.IOBridge)`, so
 * [flush] is invariably called from `Dispatchers.IO`; we always cross
 * threads to reach the EL and there is no fast path to take.
 *
 * Pipeline handlers can throw inside [emitBody] (encoder state errors,
 * transport write failure on a peer disconnect, etc.). The dispatched
 * `Runnable` runs as raw work on the EL — outside any coroutine — so an
 * uncaught exception would propagate up the EL's `drainTasks` loop and
 * kill the daemon. The dispatch wrapper catches and stores the cause in
 * [closeCause], marking the channel closed; subsequent [flush] /
 * [flushAndClose] calls rethrow the cause to the caller. This mirrors the
 * behaviour of the previous `withContext(ioDispatcher)` path which
 * propagated the exception synchronously, but delays the user-visible
 * error by one operation.
 *
 * **Lifecycle**:
 * - [flush] is suspending and awaits engine-side completion ordering only
 *   for the dispatcher hop, not for the actual write callback (those are
 *   asynchronous on push-mode engines).
 * - [flushAndClose] sends [HttpBodyEnd], requests a final flush, then calls
 *   [PipelinedChannel.awaitFlushComplete] so the connection cannot tear
 *   down before the terminator hits the wire (same K22-style race that
 *   [io.github.fukusaka.keel.server.ktor.KeelApplicationResponse.respondFromBytes]
 *   guards against).
 * - [close] (deprecated synchronous path) submits the same termination
 *   sequence as a fire-and-forget job on [scope]; the connection handler
 *   awaits the response cycle separately, so this is best-effort.
 * - [cancel] marks the channel closed and skips body emission. Pending
 *   buffered bytes are discarded.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class KeelByteWriteChannel(
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

    /**
     * Drains [internalBuffer] and dispatches one
     * `requestWrite(HttpBody) + requestFlush()` pair on
     * [PipelinedChannel.ioDispatcher].
     *
     * Returns immediately if the buffer is empty (no engine traffic for an
     * empty user flush — matches the no-op behaviour of the old bridge).
     */
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
            emitBody(bytes)
        }
    }

    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        // Snapshot remaining bytes (if any) on the calling thread.
        val remaining = if (internalBuffer.exhausted()) null else internalBuffer.readByteArray()
        scope.launch(pipelinedChannel.ioDispatcher) {
            if (remaining != null) emitBody(remaining)
            terminate()
        }
    }

    override suspend fun flushAndClose() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        drainAndDispatch()
        terminate()
    }

    override fun cancel(cause: Throwable?) {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        closeCause.store(cause)
        // Discard any buffered bytes — the connection is going away.
        if (!internalBuffer.exhausted()) internalBuffer.readByteArray()
        terminated.store(true)
    }

    private fun drainAndDispatch() {
        if (internalBuffer.exhausted()) return
        val bytes = internalBuffer.readByteArray()
        // Fire-and-forget submit to the EventLoop. Ktor's
        // `BaseApplicationResponse.respondWriteChannelContent` wraps the
        // user's `writeTo` lambda in `withContext(Dispatchers.IOBridge)`,
        // so [flush] is invariably called from `Dispatchers.IO` and we
        // always cross threads to reach the EL. [emitBody] only enqueues
        // `pipeline.requestWrite + requestFlush` (both async — bytes are
        // not on the wire when they return), so the caller does not need
        // to wait for completion; submitting via [CoroutineDispatcher.dispatch]
        // drops the per-frame `withContext` round-trip that previously
        // consumed the bulk of CPU time inside
        // `kotlinx.coroutines.scheduling.CoroutineScheduler` (work-stealing
        // / parking / unparking).
        //
        // FIFO ordering on the EL keeps body chunks ahead of the trailing
        // [HttpBodyEnd] terminator that [terminate] enqueues with a real
        // suspend.
        //
        // [emitBody] runs as a raw `Runnable` on the EL thread, outside
        // any coroutine, so an uncaught exception would propagate up
        // [drainTasks] and kill the EL daemon. Pipeline handlers can
        // legitimately throw (encoder state errors, transport write
        // failures on a peer disconnect, etc.), so catch and surface the
        // failure via [closeCause]: subsequent [flush] / [flushAndClose]
        // calls observe the cause and rethrow it to the caller. This
        // mirrors the behaviour of the previous `withContext(ioDispatcher)`
        // path which would have rethrown in the suspending caller, but
        // delays the user-visible error by one operation.
        pipelinedChannel.ioDispatcher.dispatch(kotlin.coroutines.EmptyCoroutineContext) {
            try {
                emitBody(bytes)
            } catch (e: Throwable) {
                closeCause.compareAndSet(expectedValue = null, newValue = e)
                closed.store(true)
                if (e is Error) throw e
            }
        }
    }

    /**
     * Wraps the recorded close cause into a fresh [Throwable] for each
     * user-visible throw, mirroring Ktor's
     * `io.ktor.utils.io.CloseToken.wrapCause` pattern (the class is
     * internal to `ktor-io`, so we re-implement the same policy here).
     * Throwing the same `Throwable` instance repeatedly mutates its
     * stack trace and lets `addSuppressed` accumulate across catch
     * sites; wrapping keeps each surfaced exception independent.
     *
     * Branch table matches Ktor's `CloseToken.wrapCause`:
     * - [kotlinx.coroutines.CancellationException] → fresh
     *   `CancellationException(message, origin)` so structured-concurrency
     *   cancellation propagation still recognises it.
     * - [kotlinx.coroutines.CopyableThrowable] → `createCopy()` so the
     *   coroutine framework's stack-trace recovery works across hops.
     *   Falls back to the original instance if `createCopy()` returns
     *   `null` (the contract for opting out of copying).
     * - Any other throwable → wrapped in Ktor's standard
     *   [io.ktor.utils.io.ClosedWriteChannelException].
     */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun wrapClosedCause(cause: Throwable): Throwable = when (cause) {
        is kotlinx.coroutines.CancellationException ->
            kotlinx.coroutines.CancellationException(cause.message, cause)
        is kotlinx.coroutines.CopyableThrowable<*> ->
            cause.createCopy() ?: cause
        else -> io.ktor.utils.io.ClosedWriteChannelException(cause)
    }

    /**
     * Emits a single [HttpBody] message + flush request through the
     * pipeline. Caller MUST be on [PipelinedChannel.ioDispatcher].
     */
    private fun emitBody(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val ioBuf = pipelinedChannel.allocator.allocate(bytes.size)
        ioBuf.writeByteArray(bytes, 0, bytes.size)
        pipelinedChannel.pipeline.requestWrite(HttpBody(ioBuf))
        pipelinedChannel.pipeline.requestFlush()
    }

    /**
     * Sends the [HttpBodyEnd] terminator and awaits the final flush so the
     * connection handler cannot reuse the socket while bytes are still in
     * flight on push-mode engines (NWConnection, Netty). Idempotent.
     */
    private suspend fun terminate() {
        if (!terminated.compareAndSet(expectedValue = false, newValue = true)) return
        withContext(pipelinedChannel.ioDispatcher) {
            pipelinedChannel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
            pipelinedChannel.pipeline.requestFlush()
            pipelinedChannel.awaitFlushComplete()
        }
    }
}
