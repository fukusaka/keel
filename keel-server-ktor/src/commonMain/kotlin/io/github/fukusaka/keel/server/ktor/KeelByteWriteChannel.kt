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
import kotlin.coroutines.coroutineContext

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
 * **Dispatch**: every drain hops to [PipelinedChannel.ioDispatcher] via
 * [withContext]. On Native engines (kqueue / epoll / io_uring) the
 * application coroutine already runs on the EventLoop thread, so the hop is
 * a no-op. On JVM transports the hop crosses to the worker thread; this is
 * an intentional cost to make per-frame flushes propagate (vs. the old
 * bridge that batched them).
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

    override val closedCause: Throwable? get() = closeCause.load()

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

    private suspend fun drainAndDispatch() {
        if (internalBuffer.exhausted()) return
        val bytes = internalBuffer.readByteArray()
        // Fast path: if the calling coroutine already runs on the ioDispatcher
        // (the default for keel-server-ktor — see
        // KeelApplicationEngine.Configuration.applicationDispatcher KDoc), skip
        // the withContext wrapper so each per-frame flush avoids the
        // continuation rebuild that withContext does even when no dispatch is
        // needed. On Native, the saved overhead is large enough that this fast
        // path turns ktor-keel SSE from ~450 req/s back up to the per-frame
        // baseline shared with pipeline-http (~4 K req/s on macOS kqueue).
        val ctx = coroutineContext
        if (pipelinedChannel.ioDispatcher.isDispatchNeeded(ctx)) {
            withContext(pipelinedChannel.ioDispatcher) {
                emitBody(bytes)
            }
        } else {
            emitBody(bytes)
        }
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
