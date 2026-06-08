package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.debug
import kotlin.concurrent.Volatile

/**
 * Base class for [IoTransport] implementations with shared defaults.
 *
 * Provides:
 * - **Write buffering (ownership transfer)**: [write] takes ownership of the
 *   caller's buffer reference and enqueues it into [pendingWrites]. Subclasses
 *   implement [flush] to drain the queue via platform syscalls and release
 *   each buffer after successful transmission. The caller must not touch the
 *   buffer after [write] returns.
 * - **Write backpressure**: [pendingBytes] / [isWritable] / [updatePendingBytes]
 *   track buffered data and invoke [onWritabilityChanged] at high/low water marks.
 * - **Open state**: [opened] flag with [isOpen] property for idempotent close.
 * - **Callback properties**: [onRead], [onReadClosed], [onFlushComplete],
 *   [onWritabilityChanged] initialized to `null`.
 * - **Defaults**: [awaitPendingFlush] = no-op, [awaitClosed] = no-op.
 *
 * Engine implementations extend this class and override platform-specific
 * members: [readEnabled] setter, [flush], [shutdownOutput], [close].
 *
 * @param allocator Buffer allocator for read operations.
 */
abstract class AbstractIoTransport(
    override val allocator: BufferAllocator,
) : IoTransport {

    // --- Open state ---

    /**
     * Transport open state.
     *
     * Written by [close] once (idempotent transition true → false) and
     * read by [isOpen], [write], and subclass flush paths. `@Volatile`
     * guarantees that a `false` written on the EventLoop thread is
     * visible to a caller that reads [isOpen] on another dispatcher.
     *
     * Subclasses MUST flip this flag only from the EventLoop thread
     * (or the engine-local equivalent) to keep the `pendingWrites` /
     * `pendingBytes` mutations below serialised.
     */
    @Volatile
    protected var opened = true
    override val isOpen: Boolean get() = opened

    /**
     * Marks this transport as closing by flipping [opened] from `true` to
     * `false` and returning whether this invocation initiated the
     * transition.
     *
     * Subclass [close] implementations should call this **synchronously**
     * at the top of the method so that callers on any thread observe
     * `isOpen = false` as soon as `close()` returns, independently of
     * when the EventLoop-side resource teardown actually runs.
     *
     * Not a compare-and-swap: two concurrent callers may both see
     * `opened = true`, both write `false`, and both return `true`.
     * Final idempotency of the teardown body is provided by
     * [markTeardownStarted], which is EventLoop-local and therefore
     * race-free.
     */
    protected fun markClosing(): Boolean {
        if (!opened) return false
        opened = false
        return true
    }

    /**
     * Teardown-side idempotency flag. Touched only from the owning
     * EventLoop (or the engine-specific serial dispatch queue), so no
     * volatile / atomic is required.
     */
    private var teardownStarted = false

    /**
     * Returns `true` exactly once — for the first teardown invocation
     * on the EventLoop. Subsequent calls return `false` so subclasses
     * can collapse concurrent teardown dispatches into a single cleanup
     * pass. **MUST** be invoked from the owning EventLoop thread.
     */
    protected fun markTeardownStarted(): Boolean {
        if (teardownStarted) return false
        teardownStarted = true
        return true
    }

    // --- Read path callbacks ---

    override var onRead: ((IoBuf) -> Unit)? = null
    override var onReadClosed: (() -> Unit)? = null

    // --- Idle (no-progress) timeout — time-axis defence (see EventLoopTimer) ---

    /**
     * The owning EventLoop's timer, or `null` if this engine does not yet support
     * deadline timeouts. Engine subclasses that own a [DeadlineScheduler] (or wrap
     * a native scheduler) override this; the default leaves idle timeouts inert, so
     * an unwired engine silently ignores [idleTimeoutMillis] rather than failing.
     */
    protected open val eventLoopTimer: EventLoopTimer? get() = null

    /**
     * Effective idle (no-progress) read timeout in milliseconds for this
     * connection (`0` = disabled). Engine subclasses override it from the resolved
     * per-connection config value.
     */
    protected open val idleTimeoutMillis: Long get() = 0

    private var idleHandle: TimerHandle? = null

    /**
     * Arms the read-side idle timeout if configured (> 0) and supported (the engine
     * provides an [eventLoopTimer]). Idempotent — a no-op if already armed, disabled,
     * or unsupported. Engine subclasses call this when the connection starts waiting
     * to read (so the accept-to-first-byte window is covered). **EventLoop thread.**
     */
    protected fun armIdleTimeout() {
        if (idleHandle != null) return
        val timer = eventLoopTimer ?: return
        val millis = idleTimeoutMillis
        if (millis <= 0) return
        idleHandle = timer.schedule(millis) { onIdleTimeout() }
    }

    /**
     * Refreshes the idle deadline — called by engine subclasses on every read that
     * delivers bytes, so an actively progressing connection never fires. No-op when
     * the timeout is not armed. **EventLoop thread.**
     */
    protected fun touchIdleTimeout() {
        idleHandle?.touch()
    }

    /**
     * Cancels and clears the idle timeout. Called when the connection stops waiting
     * to read (back-pressure) and on close/teardown. Idempotent. **EventLoop thread.**
     */
    protected fun cancelIdleTimeout() {
        idleHandle?.cancel()
        idleHandle = null
    }

    private fun onIdleTimeout() {
        idleHandle = null // already fired and removed by the scheduler
        // Notify the pipeline / caller of inactivity, then force the connection
        // closed. Unlike a cooperative peer-FIN — which `onReadClosed` deliberately
        // leaves open for a Coroutine-mode caller or an empty pipeline (half-close
        // support, caller owns the resource) — an idle timeout exists to *reclaim*
        // the connection from a non-cooperating peer, so it must release the fd in
        // every mode. `close()` is idempotent, so this is a no-op when the channel
        // already closed itself in pipeline mode.
        onReadClosed?.invoke()
        close()
    }

    private var writeIdleHandle: TimerHandle? = null

    /**
     * Arms the write-side idle timeout — the slow-read defence. Engine subclasses
     * call this when a flush leaves data unsent (the peer's receive window is full,
     * so the write made no progress), the only point a write actually stalls. Arming
     * here rather than on every enqueue keeps the fast path — a write that flushes
     * immediately — free of a per-write timer allocation. Shares [idleTimeoutMillis]
     * with the read side: one knob, two independent timers. Idempotent; a no-op if
     * already armed, disabled, or unsupported. **EventLoop thread.**
     */
    protected fun armWriteIdleTimeout() {
        if (writeIdleHandle != null) return
        val timer = eventLoopTimer ?: return
        val millis = idleTimeoutMillis
        if (millis <= 0) return
        writeIdleHandle = timer.schedule(millis) { onWriteIdleTimeout() }
    }

    /** Cancels and clears the write-side idle timeout (writes drained / teardown). Idempotent. */
    protected fun cancelWriteIdleTimeout() {
        writeIdleHandle?.cancel()
        writeIdleHandle = null
    }

    private fun onWriteIdleTimeout() {
        writeIdleHandle = null // already fired and removed by the scheduler
        // Pending writes have not drained for the whole timeout: the peer is not
        // reading (slow-read / stalled receive window), holding the connection and
        // its buffered response. Reclaim it exactly like the read idle timeout —
        // notify inactivity, then force-close in every channel mode.
        onReadClosed?.invoke()
        close()
    }

    // --- Write path callbacks ---

    override var onFlushComplete: (() -> Unit)? = null
    override var onWritabilityChanged: ((Boolean) -> Unit)? = null

    // --- Write buffering ---

    /**
     * Queue of owned buffers awaiting [flush].
     *
     * [write] appends to the tail; [flush] implementations drain it
     * via platform-specific syscalls and release each buffer after
     * successful transmission. Subclasses use [ArrayDeque.addFirst]
     * to re-enqueue the partial-write remainder at the head — that
     * is the operation [ArrayDeque] makes O(1) and `MutableList`
     * makes O(n).
     */
    protected val pendingWrites = ArrayDeque<PendingWrite>()

    /**
     * Buffers [buf] for the next [flush] call under ownership-transfer
     * semantics: the transport takes over the caller's reference and
     * releases it after the buffer has been flushed (or the transport is
     * torn down). The caller must not touch [buf] after this call returns.
     *
     * Captures (readerIndex, readableBytes) as a snapshot so [flush]
     * implementations can read the intended byte range regardless of
     * later pipeline activity.
     *
     * Empty writes release the buffer immediately — the caller still
     * transferred ownership, and there is nothing to enqueue.
     */
    override fun write(buf: IoBuf) {
        // Discard writes that arrive after close() — the fd is already released
        // and may have been reused by a new connection. Writing to a reused fd
        // would silently corrupt the new connection's data stream.
        if (!opened) {
            buf.release()
            return
        }
        val bytes = buf.readableBytes
        if (bytes == 0) {
            buf.release()
            return
        }
        val offset = buf.readerIndex
        pendingWrites.add(PendingWrite(buf, offset, bytes))
        updatePendingBytes(bytes)
    }

    // --- Write backpressure ---

    /**
     * Total bytes buffered in [pendingWrites] but not yet flushed.
     *
     * Incremented by [write], decremented by [updatePendingBytes] after
     * flush (partial or complete). Drives [isWritable] state transitions.
     */
    protected var pendingBytes: Int = 0

    /**
     * Per-transport writability flag, flipped by [updatePendingBytes] when
     * [pendingBytes] crosses [IoTransport.DEFAULT_HIGH_WATER_MARK] (→ `false`)
     * or [IoTransport.DEFAULT_LOW_WATER_MARK] (→ `true`).
     *
     * `@Volatile` because [isWritable] is read off-EL by the
     * `AbstractPipelinedWriteChannel.flush` backpressure gate (running on
     * Ktor's `Dispatchers.IO`). Without the annotation a JIT-cached `true`
     * could keep the producer dispatching past the high-water mark even
     * after the EL flipped the flag to `false`, defeating the gate. The
     * write side stays single-threaded (only the EL calls
     * [updatePendingBytes]) so a plain `@Volatile` is sufficient — no
     * atomic CAS is needed.
     */
    @Volatile
    private var writable: Boolean = true
    override val isWritable: Boolean get() = writable

    /**
     * Adjusts [pendingBytes] by [delta] and checks water mark thresholds.
     *
     * Called by subclass [flush] implementations after sending data
     * (negative delta) or by [write] after buffering (positive delta via
     * [write]). Triggers [onWritabilityChanged] when crossing thresholds.
     */
    protected fun updatePendingBytes(delta: Int) {
        pendingBytes += delta
        // Write-idle (slow-read) timer: a negative delta is flush progress, so a
        // partial drain that leaves data refreshes the deadline and a full drain
        // cancels it. Arming is the engine's job (only when a flush stalls), so a
        // `touch` before the timer is armed is a harmless no-op.
        if (delta < 0) {
            if (pendingBytes == 0) cancelWriteIdleTimeout() else writeIdleHandle?.touch()
        }
        if (writable && pendingBytes >= IoTransport.DEFAULT_HIGH_WATER_MARK) {
            writable = false
            onWritabilityChanged?.invoke(false)
        } else if (!writable && pendingBytes < IoTransport.DEFAULT_LOW_WATER_MARK) {
            writable = true
            onWritabilityChanged?.invoke(true)
        }
    }

    // --- Slow-path instrumentation (single-thread invariant; non-atomic) ---
    //
    // Counters incremented by subclass flush implementations to make
    // partial-write firing rate observable from the outside. Used by the
    // project's slow-path benchmark scenarios (real-network, congestion-
    // injected) to verify that an A/B run actually exercises the
    // partial-write path that the optimisation under evaluation targets,
    // rather than silently testing the fast path on loopback.
    //
    // Touched only from the owning EventLoop thread (or the engine-local
    // serial dispatch queue), matching the same invariant as
    // `pendingBytes` / `pendingWrites` / `teardownStarted`. Plain `Long`
    // suffices — no atomic / volatile required.
    //
    // Subclasses MUST increment [flushCount] for every flush call (gather
    // or single, regardless of outcome) and [partialWriteCount] for every
    // observed partial write (i.e. `writtenBytes < totalBytes` after a
    // successful `write`/`writev` syscall). Failed / WouldBlock outcomes
    // do not count as partial writes.

    /**
     * Total `flush` syscall invocations on this transport. Includes both
     * single-buffer and gather paths regardless of outcome (success, partial,
     * WouldBlock, Failed). Stays at zero on read-only transports.
     */
    protected var flushCount: Long = 0

    /**
     * Number of `flush` invocations that observed a partial write
     * (`writtenBytes < totalBytes` from a successful `write`/`writev`).
     * The ratio `partialWriteCount / flushCount` is the empirical
     * partial-write firing rate for this transport's lifetime.
     */
    protected var partialWriteCount: Long = 0

    /**
     * Logs the slow-path instrumentation counters on transport teardown.
     * Subclass [close] implementations call this from inside the
     * EventLoop-thread teardown body (after the resource is fully
     * released) so the counts reflect the entire transport lifetime.
     *
     * Emitted at debug level — no overhead in production where debug
     * logging is disabled.
     */
    protected fun logTransportStatsOnClose(logger: Logger, fdLabel: String) {
        if (flushCount == 0L) return
        // Ratio expressed as basis points (1/10000) to avoid `String.format`
        // dependency on the K/N commonMain target. Consumers (bench scripts,
        // analysis tools) divide by 100.0 for the human-readable percentage.
        val ratioBp = if (partialWriteCount > 0L) {
            (partialWriteCount * 10_000L / flushCount).toInt()
        } else {
            0
        }
        logger.debug {
            "transport stats: $fdLabel flush=$flushCount partial=$partialWriteCount ratio_bp=$ratioBp"
        }
    }

    // --- Defaults ---

    override suspend fun awaitPendingFlush() {}
    override suspend fun awaitClosed() {}

    /**
     * Snapshot of a buffered write: the [IoBuf] (owned by the transport),
     * the byte offset where readable data starts, and the number of bytes
     * to write.
     *
     * Offset/length are recorded separately so that [flush] implementations
     * always see the range that was current at [write] time, independent of
     * any subsequent read-side mutation to the buffer's indices.
     */
    class PendingWrite(val buf: IoBuf, val offset: Int, val length: Int)
}
