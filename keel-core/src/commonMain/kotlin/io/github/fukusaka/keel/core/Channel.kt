package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.github.fukusaka.keel.io.BufferedSuspendSource
import io.github.fukusaka.keel.io.OwnedSuspendSource
import io.github.fukusaka.keel.io.SuspendSink
import io.github.fukusaka.keel.io.SuspendSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * A bidirectional byte channel backed by a network connection.
 *
 * ```
 * Layer          API                       Copy
 * -----          ---                       ----
 * Engine layer:  read/write(IoBuf)       0  (zero-copy via unsafePointer)
 * Codec layer:   asSuspendSource/Sink()      0  (IoBuf direct, zero-copy)
 * ```
 *
 * **Write/flush separation**: [write] buffers data without sending.
 * [flush] sends all buffered data to the network. This enables
 * writev/gather-write optimisation when multiple writes precede
 * a single flush.
 *
 * **Half-close**: only [shutdownOutput] is provided.
 * Input-side EOF is detected by [read] returning -1.
 * [shutdownInput] was omitted (YAGNI): Netty/Go use it only in tests,
 * and NWConnection/Node.js have no explicit support.
 *
 * **Lifecycle**: [isOpen] tracks whether the fd is still open.
 * [isActive] tracks whether the channel is connected and ready for I/O.
 * Both become false after [close].
 */
interface Channel : AutoCloseable {

    /**
     * The buffer allocator for this channel.
     *
     * Buffers are cheapest to allocate and release on this channel's
     * EventLoop: with the default pooled allocator the owning EventLoop hits a
     * lock-free freelist fast path. A release from another thread is still safe
     * (the pooled allocator routes it to the owning context via its
     * confinement) but takes the slower cross-context path.
     */
    val allocator: BufferAllocator

    /** Remote address of the peer, or null if not connected. */
    val remoteAddress: SocketAddress?

    /** Local address this channel is bound to, or null if not bound. */
    val localAddress: SocketAddress?

    // --- Lifecycle ---

    /** True if the underlying transport is open (not yet fully closed). */
    val isOpen: Boolean

    /** True if the channel is connected and ready for read/write. */
    val isActive: Boolean

    /**
     * Suspends until this channel is fully closed.
     * Returns immediately if the channel is already closed.
     */
    suspend fun awaitClosed()

    // --- Zero-copy I/O (engine layer) ---

    /**
     * Reads bytes into [buf] starting at its [IoBuf.writerIndex].
     * Advances [IoBuf.writerIndex] by the number of bytes read.
     *
     * Engine implementations pass [IoBuf.unsafePointer] (Native) or
     * [IoBuf.unsafeBuffer] (JVM) directly to the OS read syscall
     * for zero-copy I/O.
     *
     * The peer's end of file is `-1`, returned once everything it sent has
     * been read. The channel is still open and writable then — the peer
     * half-closed — and closing it is the caller's; a channel with handlers
     * that hears that end of file before its first read is a Pipeline-mode
     * channel to its pipeline at that moment and closes itself instead.
     * Where the transport reports that it ended the connection itself — an
     * idle reclamation does, and each other end (a reset, a failed read or
     * write, a stopped loop) once its engine reports it that way — the
     * channel is closed, a read is `-1`, and a [write] or [flush] that finds
     * it closed throws [IllegalStateException]; one already past that check
     * is discarded with the transport. Nothing can be sent. An end the
     * transport does not report that way still closes the channel, and a
     * read racing that close throws rather than answering `-1`.
     *
     * @return number of bytes read, or -1 on EOF.
     */
    suspend fun read(buf: IoBuf): Int

    /**
     * Writes bytes from [buf] between [IoBuf.readerIndex] and [IoBuf.writerIndex].
     * Advances [IoBuf.readerIndex] by the number of bytes consumed.
     * Data is buffered until [flush] is called.
     *
     * **Ownership (transfer)**: this method takes ownership of [buf] from the
     * caller. The caller must not touch the buffer after this call returns —
     * no further read/write, no [IoBuf.release], and no index inspection.
     * The transport releases the buffer after [flush] completes (or on teardown).
     * If the caller wants to keep a reference alive (for example, to write the
     * same data to multiple channels), it must call [IoBuf.retain] **before**
     * passing the buffer in. The transfer holds in every outcome: a write
     * that throws — the channel is closed, [IllegalStateException]; the
     * caller was cancelled — has released [buf] or handed it on, and the
     * caller has nothing left to release.
     *
     * @return number of bytes written to the outbound buffer.
     */
    suspend fun write(buf: IoBuf): Int

    /**
     * Flushes all buffered outbound data to the network and suspends
     * until all bytes are sent.
     *
     * Default implementation calls [requestFlush] + [awaitFlushComplete].
     * An engine may override this directly instead, and then owes neither —
     * none in this tree does today.
     *
     * For fire-and-forget flushing (no completion wait), call
     * [requestFlush] directly.
     *
     * **May raise.** A send the platform refused outright is a failure, not a
     * completed flush, and the bytes it was carrying are gone.
     *
     * **It never travels back through the request half.** [requestFlush]
     * runs the drain through the pipeline, which converts a handler failure
     * into an error event rather than returning it — so it is
     * [awaitFlushComplete], the half that waits, that raises. See there for
     * when it has something left to observe and when it does not.
     *
     * Engines differ in whether they report a refused send at all, and this
     * is the contract they are converging on rather than one they all meet;
     * converging them is tracked.
     */
    suspend fun flush() {
        requestFlush()
        awaitFlushComplete()
    }

    /**
     * Initiates a flush of all buffered outbound data without waiting
     * for completion (fire-and-forget).
     *
     * Data is submitted to the OS send buffer (or queued for async
     * send on EAGAIN). Use [awaitFlushComplete] to wait for all
     * pending data to be sent.
     *
     * Default: throws [UnsupportedOperationException]. Engines that use
     * the [requestFlush] + [awaitFlushComplete] pattern must override.
     * Engines that override [flush] directly do not need this.
     */
    fun requestFlush() {
        throw UnsupportedOperationException(
            "requestFlush() not implemented. Override flush() or requestFlush()+awaitFlushComplete().",
        )
    }

    /**
     * Suspends until all pending flush operations complete.
     *
     * Returns immediately if no async flush is pending (i.e., the
     * last [requestFlush] completed synchronously).
     *
     * Default: no-op (assumes [flush] override handles completion).
     * Engines that use the [requestFlush] + [awaitFlushComplete] pattern
     * must override.
     *
     * **May raise, and with either of two kinds of failure.**
     *
     * The send's own, whenever a failing drain is still there to be
     * observed: one this call runs itself, one it re-drives because the last
     * drain threw with the queue left behind, one that fails elsewhere while
     * this call is parked, or one that already ended the connection before
     * this call began. **Whether the wait had started when the send was
     * refused does not change the answer** — a caller did not choose which
     * of those it was and cannot read it afterwards.
     *
     * Or the connection's, for the other ways it can end without the bytes
     * going out. A failure that ended it — work on its behalf that threw, a
     * read the platform refused for good — arrives as
     * [ConnectionFailureException]; a loop that ended without being asked to
     * arrives as [EngineFailureException]. Each is recorded where it happens,
     * so a wait arriving after one of them is told what a wait already parked
     * was told — for a refused send, the same object. A drain that fails some
     * other way answers the parked wait with what it caught and the later one
     * with the record made from it, so the two agree about the connection and
     * not about the type. Unless the caller was already closing, in which case
     * the drain answers no parked wait at all and leaves both to the close.
     * The types can also differ the other way: a refused send met while the
     * connection already has a reason answers the parked wait with the refusal
     * and the later one with that earlier reason, since a connection ends once
     * and the first failure is what ended it.
     *
     * **Engines differ in how far they have taken this**, the same divergence
     * [flush] states about a refused send: the two POSIX readiness engines
     * record these and answer with them, and the rest still end such a wait as
     * a cancellation. This is the contract they are converging on rather than
     * one they all meet.
     *
     * **A `CancellationException` is what remains**, and it means nothing that
     * ended this connection was recorded *and consulted*: the caller closed
     * this channel, or the engine was asked to stop, or the transport ended it
     * on a policy the application configured, such as an idle timeout
     * reclaiming a connection nobody is using — or the peer ended the exchange
     * in an orderly way, which is not this transport failing at all. Ending
     * work you started is what cancellation means, and the first of those is
     * exactly that; the rest are ends nobody asked this caller about. The
     * second can also arrive over a connection that *had* recorded a failure,
     * which the paragraph on a gone loop describes.
     *
     * That last one makes a distinction worth knowing, and it turns on
     * something the caller does control. A reset is told apart from an orderly
     * close by the read that refuses; a connection with reads disabled issues
     * none, so both arrive as the same event and neither is recorded. With
     * reads enabled the reset is recorded and the orderly close is not. So the
     * same connection dying two ways can answer a wait two ways, or the same
     * way, depending on whether anything was reading — even though what became
     * of the queued bytes is the same throughout.
     *
     * **Whether any of them arrives depends on there being something left to
     * report.** A drain that ran inside the request and emptied the queue
     * leaves this call nothing to find, so it returns normally: a [flush]
     * that met the failure raised it there, and a pipelined one took it to
     * the error path. This is therefore not a way to ask, after the fact,
     * whether the last flush reached the peer.
     *
     * **A failure that ended the connection is the exception.** The
     * connection stays ended, and that is a state this call still finds
     * afterwards — so a wait arriving late is told what ended it rather than
     * returning normally. Which call ran the drain does not enter into it.
     *
     * **A loop that is gone answers for a connection still open.** A wait the
     * stop finds parked, and one arriving once the loop has gone quiet, hear
     * about the loop — a cancellation when it was asked to stop,
     * [EngineFailureException] when it was not. So does one arriving while the
     * loop is still winding down over a connection that is still open with
     * bytes queued: nothing will drain them now, and what is gone is bigger
     * than this connection. Once the loop has gone quiet this is the whole
     * answer — the connection's own state is not consulted at all. So a
     * connection that failed, on an engine later asked to stop, answers a wait
     * arriving afterwards with the cancellation the stop earns, even though
     * the connection knew why those bytes never left; and a connection its
     * caller closed answers with the loop's failure rather than the close,
     * where a wait arriving a moment earlier would have heard the close. Which
     * of the two such a wait should hear is not settled, and the moment that
     * currently decides it is not one a caller can see.
     *
     * **A loop whose registration lock failed to *release* answers nobody.**
     * The lock stays held by whichever thread failed to give it back, and the
     * terminal sequence declines to walk ledgers it can no longer guard, so
     * waits already parked on that loop stay parked. Nor is a wait arriving
     * afterwards reliably told: the sequence's last drain runs ahead of that
     * decision, and anything queued there that needs the lock does not return,
     * which leaves the loop never publishing that it stopped. That is an
     * ending this contract does not cover, and the code that stops there says
     * so.
     */
    suspend fun awaitFlushComplete() {}

    // --- Dispatcher ---

    /**
     * The [CoroutineDispatcher] best suited for I/O operations on this channel.
     *
     * Engines with dedicated EventLoop threads (NIO, kqueue, epoll) return
     * their EventLoop's dispatcher, enabling coroutines to run on the same
     * thread that drives I/O — eliminating cross-thread dispatch overhead.
     *
     * Default: [Dispatchers.Default] for engines without a dedicated EventLoop.
     * (Dispatchers.IO is not available in commonMain due to JS target.)
     */
    val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default

    // --- Half-close ---

    /**
     * Shuts down the write side of this channel (TCP FIN),
     * signalling that no more output will be sent.
     * The read side remains open for consuming the peer's remaining data.
     *
     * **Safe to call from any thread, and the FIN may be sent after this
     * returns.** Engines that own an EventLoop issue the syscall on that
     * thread, so an off-loop caller only queues the request.
     *
     * Buffered writes are sent first: whatever [write] queued before this
     * call reaches the peer ahead of the FIN, so an explicit [flush] is not
     * required. Writes issued *after* it are discarded — the caller declared
     * it had nothing more to send. Use [awaitFlushComplete] to observe the
     * data leaving; the FIN follows it.
     *
     * Ordering the FIN behind the data also makes it as slow as the data: a
     * peer that stops reading holds both back, bounded only by the engine's
     * idle timeout (disabled by default). Call [close] instead when the FIN
     * has to go out regardless — it supersedes a pending half-close and
     * discards what was still queued.
     *
     * **Does not raise when the send is refused**, even though the buffered
     * writes go first and this call may be the one that meets the refusal.
     * Which call meets it depends on where the drain ran — in place, or on a
     * later tick under flush coalescing, on by default in the readiness
     * engines — and a caller cannot know which it got. So the answer does not
     * depend on it: the connection ends and no FIN follows the refused bytes.
     * The reason reaches [awaitFlushComplete], and a pipeline handler's
     * `onError` ahead of its `onInactive` — the two places a reported
     * refusal is delivered to. The ordering promise is between the two
     * handler callbacks; the wait's resume rides its own dispatcher and is
     * not ordered against them. One met while the caller is already closing
     * still answers the wait but is not an error to report.
     *
     * A failure that is not the refusal is not contained — a drain that also
     * could not release its buffers. It follows the drain, like the refusal
     * does: this call receives it only when the drain ran inside it, which
     * it does not under flush coalescing — on by default in the readiness
     * engines — and does not for a caller off the engine's thread, whose
     * half-close is handed to that thread and runs after this call has
     * returned. The engine reports it in both of those. A failure of the
     * wind-down that follows the refusal reaches this call on no
     * configuration: it happens after the refusal was handed to its waiters,
     * so it rides nothing — the engine's own log is its record.
     */
    fun shutdownOutput()

    // --- Suspend I/O bridge (codec layer, zero-copy) ---

    /**
     * Returns a [SuspendSource] view for reading from this channel.
     *
     * Zero-copy: delegates to [read] which writes directly into [IoBuf].
     * Use [BufferedSuspendSource] to wrap the result for readLine/readByte.
     *
     * Default implementation delegates to [read] via [SuspendChannelSource].
     * Engines can override for specialized implementations (e.g., io_uring).
     */
    fun asSuspendSource(): SuspendSource = SuspendChannelSource(this)

    /**
     * Returns a [SuspendSink] view for writing to this channel.
     *
     * Zero-copy: delegates to [write]/[flush] which read directly from [IoBuf].
     * Use [BufferedSuspendSink] to wrap the result for writeString/writeByte.
     *
     * Default implementation delegates to [write]/[flush] via [SuspendChannelSink].
     */
    fun asSuspendSink(): SuspendSink = SuspendChannelSink(this)

    /**
     * Returns a [BufferedSuspendSource] for codec-layer reading.
     *
     * Default implementation uses pull-mode (allocates internal buffer, 1 copy per read).
     * [PipelinedChannel] overrides to use push-mode via [SuspendBridgeHandler]'s
     * [OwnedSuspendSource], achieving zero-copy.
     */
    fun asBufferedSuspendSource(): BufferedSuspendSource =
        BufferedSuspendSource(asSuspendSource(), allocator)

    /** Closes both read and write sides and releases all resources. */
    override fun close()
}

/**
 * Default [SuspendSource] implementation that delegates to [Channel.read].
 *
 * Used by [Channel.asSuspendSource]'s default implementation. Engines can
 * override [Channel.asSuspendSource] to provide a specialized implementation
 * (e.g., io_uring completion-based reads) without changing this class.
 */
private class SuspendChannelSource(private val channel: Channel) : SuspendSource {
    override suspend fun read(buf: IoBuf): Int = channel.read(buf)

    /** No-op: channel lifecycle is managed by the caller, not by this source. */
    override fun close() {}
}

/**
 * Default [SuspendSink] implementation that delegates to [Channel.write]/[Channel.flush].
 *
 * Used by [Channel.asSuspendSink]'s default implementation. Engines can
 * override [Channel.asSuspendSink] to provide a specialized implementation
 * without changing this class.
 */
private class SuspendChannelSink(private val channel: Channel) : SuspendSink {
    override suspend fun write(buf: IoBuf): Int = channel.write(buf)
    override suspend fun flush() = channel.flush()

    /** No-op: channel lifecycle is managed by the caller, not by this sink. */
    override fun close() {}
}
