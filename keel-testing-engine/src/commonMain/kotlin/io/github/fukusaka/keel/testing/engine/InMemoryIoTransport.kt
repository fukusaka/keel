package io.github.fukusaka.keel.testing.engine

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * One half of an in-memory connected transport pair.
 *
 * An [InMemoryIoTransport] has no file descriptor and no OS socket. It is
 * cross-wired to a [peer] transport: bytes written on this side and then
 * [flush]ed surface as inbound reads on the peer (via [peer]'s [onRead]
 * callback), and vice versa. This is the "true loopback" that lets
 * [InMemoryEngine] connect a client [io.github.fukusaka.keel.core.Channel]
 * to a server [io.github.fukusaka.keel.pipeline.PipelinedChannel] entirely
 * in memory.
 *
 * ```
 * client transport            server transport
 *   write/flush  ───────────────▶  onRead → pipeline.notifyRead
 *   onRead   ◀───────────────────  write/flush (handler requestWrite)
 * ```
 *
 * **Threading**: [ioDispatcher] is [Dispatchers.Unconfined], so a coroutine
 * launched on this dispatcher runs inline on the caller's thread. A
 * [flush] therefore delivers to the peer synchronously within the calling
 * coroutine — no real EventLoop is involved.
 *
 * The [peer] reference is set once, immediately after both transports are
 * constructed, via [linkPeer]; it is never reassigned.
 *
 * @param allocator buffer allocator shared with the owning engine.
 */
internal class InMemoryIoTransport(
    allocator: BufferAllocator,
) : AbstractIoTransport(allocator) {

    /**
     * The transport this one is cross-wired to. Assigned exactly once by
     * [linkPeer] right after construction; treated as non-null afterward.
     */
    private var peer: InMemoryIoTransport? = null

    override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined

    /**
     * `true` once this side has been told (via [shutdownOutput] or [close])
     * that no more bytes will be delivered to the peer. A second
     * shutdown / a close after shutdown must not re-notify the peer.
     */
    private var outboundClosed = false

    /**
     * Inbound bytes the peer delivered while this side was not yet ready
     * to consume them — either [onRead] was still null (the channel
     * constructor had not run) or [readEnabled] was `false`. This queue is
     * the in-memory stand-in for a kernel `rcvbuf`: a real socket holds
     * such bytes until the application reads them, so the loopback must
     * too rather than dropping them. Drained in FIFO order by [drainInbound].
     */
    private val inboundQueue = ArrayDeque<IoBuf>()

    /**
     * `true` once the peer has signalled EOF but the read side was not yet
     * armed to observe it. Replayed by [drainInbound] after the queued
     * bytes, so a peer half-close that races ahead of the first `read` is
     * not lost.
     */
    private var pendingEof = false

    /**
     * Read-loop arming flag. The in-memory transport queues inbound bytes
     * in [inboundQueue] while this is `false`; flipping it to `true`
     * drains the queue so no byte the peer delivered is lost.
     */
    override var readEnabled: Boolean = false
        set(value) {
            field = value
            if (value) drainInbound()
        }

    /**
     * Cross-wires this transport to [other]. Called once by
     * [InMemoryEngine] after both halves of a connection are constructed.
     */
    fun linkPeer(other: InMemoryIoTransport) {
        peer = other
    }

    /**
     * Delivers [bytes] to this transport's read side, simulating an
     * inbound read event. Invoked by the peer's [flush].
     *
     * If the read side is ready ([onRead] wired and [readEnabled] true)
     * the bytes are surfaced immediately; otherwise they are queued in
     * [inboundQueue] and replayed by [drainInbound] once reading is armed.
     */
    private fun deliverInbound(bytes: IoBuf) {
        if (!opened) {
            bytes.release()
            return
        }
        inboundQueue.addLast(bytes)
        drainInbound()
    }

    /**
     * Surfaces every queued inbound buffer (and a [pendingEof], if any)
     * through [onRead] / [onReadClosed], in arrival order, provided the
     * read side is armed. A no-op while [onRead] is null or [readEnabled]
     * is `false` — the bytes stay queued until the next arming.
     */
    private fun drainInbound() {
        val callback = onRead ?: return
        if (!readEnabled) return
        var delivered = false
        while (inboundQueue.isNotEmpty()) {
            callback(inboundQueue.removeFirst())
            delivered = true
        }
        // The drain is this transport's batch: everything the peer had for
        // this side, handed over in one pass. A handler answering a burst
        // with one flush needs the same boundary here that a socket engine
        // gives it, or the in-memory pipe stops standing in for one.
        if (delivered) onReadComplete?.invoke()
        if (pendingEof) {
            pendingEof = false
            onReadClosed?.invoke()
        }
    }

    /**
     * Sends all buffered writes to the peer transport's read side.
     *
     * Each pending [IoBuf] is copied into a fresh buffer from [allocator]
     * so the peer owns an independent reference: the in-memory pipe does
     * not share buffer memory across the two channels, matching the
     * copy boundary a real socket imposes. Always completes synchronously.
     */
    override fun flush(): Boolean {
        flushCount++
        if (pendingWrites.isEmpty()) return true
        val target = peer
        while (pendingWrites.isNotEmpty()) {
            val pw = pendingWrites.removeFirst()
            updatePendingBytes(-pw.length)
            if (target != null && target.opened) {
                val copy = allocator.allocate(pw.length)
                pw.buf.copyTo(pw.offset, copy, pw.length)
                target.deliverInbound(copy)
            }
            pw.buf.release()
        }
        // This pipe stands in for a socket engine, so it owes a handler the
        // same completion one would send. Raised from inside `flush`, which is
        // the shape a transport that drains in place has and which the
        // callback's contract allows — and the reason `onFlushComplete`'s own
        // KDoc tells a handler not to flush from it.
        onFlushComplete?.invoke()
        return true
    }

    /**
     * Half-closes the outbound direction: the peer observes EOF on its
     * read side ([onReadClosed]) — after any buffered writes have been
     * delivered, matching the real engines. The inbound direction stays
     * open so a final response from the peer can still be read. Idempotent.
     */
    override fun shutdownOutput() {
        shutdownOutputOwned()
    }

    override fun sendFin() {
        notifyPeerEof()
    }

    /**
     * Closes this transport. Releases any un-flushed buffers, signals the
     * peer's read side EOF, and flips [isOpen] to `false`. Idempotent.
     */
    override fun close() {
        if (!markClosing()) return
        if (!markTeardownStarted()) return
        for (pw in pendingWrites) pw.buf.release()
        pendingWrites.clear()
        pendingBytes = 0
        for (buf in inboundQueue) buf.release()
        inboundQueue.clear()
        notifyPeerEof()
    }

    /**
     * Notifies the peer that no further inbound bytes will arrive, exactly
     * once over this transport's lifetime. The peer surfaces the EOF after
     * any still-queued inbound bytes — see [InMemoryIoTransport.markEof].
     */
    private fun notifyPeerEof() {
        if (outboundClosed) return
        outboundClosed = true
        peer?.takeIf { it.opened }?.markEof()
    }

    /**
     * Records that the peer has finished sending. The EOF is surfaced
     * through [onReadClosed] only after every queued inbound buffer has
     * been drained, so a half-close racing ahead of the first `read` does
     * not swallow bytes the peer sent just before closing.
     */
    private fun markEof() {
        pendingEof = true
        drainInbound()
    }

    private fun IoBuf.copyTo(srcOffset: Int, dest: IoBuf, length: Int) {
        val scratch = ByteArray(length)
        val savedReader = readerIndex
        readerIndex = srcOffset
        readByteArray(scratch, 0, length)
        readerIndex = savedReader
        dest.writeByteArray(scratch, 0, length)
    }

    companion object {
        /**
         * Builds a cross-wired pair of in-memory transports sharing
         * [allocator]. The first element is the client side, the second
         * the server side; both are linked to each other.
         */
        fun pair(allocator: BufferAllocator): Pair<InMemoryIoTransport, InMemoryIoTransport> {
            val client = InMemoryIoTransport(allocator)
            val server = InMemoryIoTransport(allocator)
            client.linkPeer(server)
            server.linkPeer(client)
            return client to server
        }
    }
}
