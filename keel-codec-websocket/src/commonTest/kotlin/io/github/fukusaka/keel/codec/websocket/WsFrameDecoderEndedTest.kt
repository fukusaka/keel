package io.github.fukusaka.keel.codec.websocket

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendMessageBridge
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.io.Buffer
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Once a connection has ended, [WsFrameDecoder] decodes no further byte.
 *
 * The ending -- `onInactive` -- reaches the decoder when the read side has
 * closed (after which the transport promises no further read) or when the
 * channel's own `close()` is raised, possibly from inside the decoder's own
 * downstream dispatch of a frame. On either path a frame decoded afterwards
 * reaches nobody who can act on it: the server's frame bridge is closed and
 * releases it unread, and a bare consumer is handed a frame it can no longer
 * answer. So the decoder ends: the rest of the read
 * that carried the ending, later reads, and reads the pipeline replays from
 * its journal are not decoded, no frame is emitted, no pooled payload is
 * allocated, and a frame left part-parsed is dropped.
 *
 * A peer's CLOSE frame precedes its FIN, and reads are delivered before the
 * read side's closing is, so the CLOSE is decoded before the ending -- a
 * case pins that nothing the closing handshake needs is lost.
 *
 * Every case runs on a [TrackingAllocator] and asserts the buffers are all
 * back.
 */
class WsFrameDecoderEndedTest {

    private val logger = PrintLogger("WsFrameDecoderEndedTest")

    /**
     * Records each frame as `opcode:payload`, releases pooled payloads, and
     * can close the channel from inside the dispatch of the n-th frame.
     */
    private class Sink(private val closeOnFrame: Int?) : InboundHandler {
        lateinit var channel: PipelinedChannel
        val frames = mutableListOf<String>()
        val errors = mutableListOf<String>()

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg !is WsFrame) error("Unexpected message: ${msg::class.simpleName}")
            val pooled = msg.inboundPayload
            val bytes = if (pooled != null) {
                val out = ByteArray(pooled.readableBytes)
                pooled.readByteArray(out, 0, out.size)
                pooled.release()
                out
            } else {
                msg.payload
            }
            frames.add(if (msg.opcode.isControl) msg.opcode.name else msg.opcode.name + ":" + bytes.decodeToString())
            if (frames.size == closeOnFrame) channel.close()
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            errors.add(cause::class.simpleName ?: "?")
        }
    }

    private class Connection(
        val tracker: TrackingAllocator,
        val transport: TestIoTransport,
        val channel: PipelinedChannel,
        val sink: Sink,
        val decoder: WsFrameDecoder,
    ) {
        fun read(bytes: ByteArray) {
            val buf = tracker.allocate(bytes.size.coerceAtLeast(1))
            if (bytes.isNotEmpty()) buf.writeByteArray(bytes, 0, bytes.size)
            transport.onRead?.invoke(buf)
        }
    }

    private fun open(
        closeOnFrame: Int? = null,
        pooled: Boolean = false,
        requireClientMasking: Boolean = true,
    ): Connection {
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        val sink = Sink(closeOnFrame)
        val channel = object : AbstractPipelinedChannel(transport, logger) {}
        sink.channel = channel
        val decoder = WsFrameDecoder(requireClientMasking = requireClientMasking, poolDataPayloads = pooled)
        channel.pipeline.addLast("decoder", decoder)
        channel.pipeline.addLast("sink", sink)
        return Connection(tracker, transport, channel, sink, decoder)
    }

    private fun bytesOf(vararg frames: WsFrame): ByteArray {
        val scratch = Buffer()
        for (f in frames) writeFrame(f, scratch)
        val size = scratch.size.toInt()
        val out = ByteArray(size)
        scratch.readAtMostTo(out, 0, size)
        return out
    }

    private fun text(s: String): WsFrame = WsFrame.text(s, maskKey = MASK)

    // --- W1 / W2: nothing is emitted after the ending, on either path ---

    @Test
    fun `frames after the ending are not decoded on the slow path`() {
        val c = open(closeOnFrame = 1)
        c.read(bytesOf(text("a"), text("b"), text("c")))
        // A later read as well: dropping the accumulator at the ending stops
        // the current read on its own, so only a read after it tells the
        // recorded ending from the drop.
        c.read(bytesOf(text("d")))
        assertEquals(listOf("TEXT:a"), c.sink.frames)
        assertEquals(emptyList(), c.sink.errors)
        assertEquals(0, c.tracker.outstandingCount, "every buffer is back")
    }

    @Test
    fun `frames after the ending are not decoded on the pooled fast path`() {
        // With pooled payloads every decoded data frame is an allocation the
        // downstream must release. After the ending none is made: the one
        // read plus the one payload are all the tracker ever hands out.
        val c = open(closeOnFrame = 1, pooled = true)
        c.read(bytesOf(text("a"), text("b"), text("c")))
        assertEquals(listOf("TEXT:a"), c.sink.frames)
        assertEquals(2, c.tracker.allocateCount, "one read buffer and one pooled payload, nothing after the ending")
        assertEquals(0L, c.decoder.pendingBytes, "and nothing of the rest is stashed for a next chunk")
        assertEquals(0, c.tracker.outstandingCount, "every buffer is back")
    }

    @Test
    fun `a close raised from a CLOSE frame stops the frames behind it`() {
        // A handler that closes the channel from inside the CLOSE frame's
        // dispatch. No in-tree handler does today -- the echo handlers echo
        // the CLOSE and leave the close to their teardown -- but the decoder
        // is public and the shape is the re-entrant one. Data the peer put
        // after its CLOSE (which the protocol forbids) is not decoded.
        val c = open(closeOnFrame = 2)
        c.read(bytesOf(text("a"), WsFrame.close(), text("late")))
        c.read(bytesOf(text("later")))
        assertEquals(listOf("TEXT:a", "CLOSE"), c.sink.frames)
        assertEquals(0, c.tracker.outstandingCount, "every buffer is back")
    }

    @Test
    fun `the ending cuts the same bytes at the same place wherever a read boundary falls on the pooled path`() {
        // The fast path parses straight out of the read and stashes only a
        // trailing partial frame; a split can land the ending in either read
        // and leave the remainder on either side of the stash.
        val whole = bytesOf(text("a"), text("b"), text("c"))
        for (cut in 1 until whole.size) {
            val c = open(closeOnFrame = 1, pooled = true)
            c.read(whole.copyOfRange(0, cut))
            c.read(whole.copyOfRange(cut, whole.size))
            assertEquals(listOf("TEXT:a"), c.sink.frames, "split at byte $cut")
            assertEquals(emptyList(), c.sink.errors, "split at byte $cut")
            assertEquals(0L, c.decoder.pendingBytes, "nothing stashed after the ending (split at $cut)")
            assertEquals(0, c.tracker.outstandingCount, "every buffer is back (split at $cut)")
        }
    }

    // --- W3: a frame left part-parsed at the ending is dropped ---

    @Test
    fun `a frame cut by the ending is dropped and its rest is not decoded`() {
        val whole = bytesOf(text("b"))
        val c = open()
        c.read(bytesOf(text("a")) + whole.copyOfRange(0, 3))
        assertEquals(3L, c.decoder.pendingBytes, "the cut frame is held before the ending")
        c.channel.close()
        assertEquals(0L, c.decoder.pendingBytes, "and dropped at it")
        c.read(whole.copyOfRange(3, whole.size) + bytesOf(text("c")))
        assertEquals(listOf("TEXT:a"), c.sink.frames)
        assertEquals(emptyList(), c.sink.errors)
        assertEquals(0L, c.decoder.pendingBytes, "a read after the ending is not copied in either")
        assertEquals(0, c.tracker.outstandingCount, "every buffer is back")
    }

    // --- W4: bytes after the ending raise no error ---

    @Test
    fun `bytes after the ending that would break the protocol raise no error`() {
        val c = open(closeOnFrame = 1)
        // An unmasked data frame (a server rejects it), then a length no
        // decoder accepts.
        val oversized = byteArrayOf(0x81.toByte(), 0x7F, 0x7F, -1, -1, -1, -1, -1, -1, -1)
        c.read(bytesOf(text("a"), WsFrame.text("unmasked")) + oversized)
        c.read(oversized)
        assertEquals(listOf("TEXT:a"), c.sink.frames)
        assertEquals(emptyList(), c.sink.errors)
        assertEquals(0, c.tracker.outstandingCount, "every buffer is back")
    }

    // --- W5: a replayed read after the ending is not decoded ---

    @Test
    fun `a journalled read replayed after the ending is not decoded`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        val queue = QueueingDispatcher()
        transport.dispatcher = queue
        val sink = Sink(closeOnFrame = 1)
        val channel = object : AbstractPipelinedChannel(transport, logger) {}
        sink.channel = channel
        val decoder = WsFrameDecoder()
        val c = Connection(tracker, transport, channel, sink, decoder)
        c.read(bytesOf(text("a")))
        c.read(bytesOf(text("b")))
        channel.pipeline.addLast("decoder", decoder)
        channel.pipeline.addLast("sink", sink)
        assertEquals(emptyList(), sink.frames, "nothing is replayed before the drain runs")
        queue.runQueued()
        assertEquals(listOf("TEXT:a"), sink.frames)
        assertEquals(0, tracker.outstandingCount, "every buffer is back")
    }

    @Test
    fun `a journalled read replayed after the ending is not decoded on the pooled path`() {
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        val queue = QueueingDispatcher()
        transport.dispatcher = queue
        val sink = Sink(closeOnFrame = 1)
        val channel = object : AbstractPipelinedChannel(transport, logger) {}
        sink.channel = channel
        val decoder = WsFrameDecoder(poolDataPayloads = true)
        val c = Connection(tracker, transport, channel, sink, decoder)
        c.read(bytesOf(text("a")))
        c.read(bytesOf(text("b")))
        channel.pipeline.addLast("decoder", decoder)
        channel.pipeline.addLast("sink", sink)
        queue.runQueued()
        assertEquals(listOf("TEXT:a"), sink.frames)
        assertEquals(
            3,
            tracker.allocateCount,
            "two journalled reads and one payload -- none for the replay after the ending",
        )
        assertEquals(0L, decoder.pendingBytes)
        assertEquals(0, tracker.outstandingCount, "every buffer is back")
    }

    @Test
    fun `an aborted drain keeps the frames behind the bad one for the next read`() {
        // Not an ending: a protocol violation in the middle of a read throws
        // out of the drain, and the whole frames behind it stay in the
        // accumulator until the next read parses them. Pinned here because
        // it is the one state of the accumulator the ending's "always zero"
        // does not cover, and the seam's description names it.
        val c = open()
        val behind = bytesOf(text("c"))
        c.read(bytesOf(text("a"), WsFrame.text("unmasked")) + behind)
        assertEquals(listOf("TEXT:a"), c.sink.frames)
        assertEquals(listOf("WsCodecException"), c.sink.errors)
        assertEquals(behind.size.toLong(), c.decoder.pendingBytes, "the frame behind the bad one is still held")
        c.read(bytesOf(text("d")))
        assertEquals(listOf("TEXT:a", "TEXT:c", "TEXT:d"), c.sink.frames)
        assertEquals(0, c.tracker.outstandingCount, "every buffer is back")
    }

    // --- W6: the cut falls at the ending wherever a read boundary falls ---

    @Test
    fun `the ending cuts the same bytes at the same place wherever a read boundary falls`() {
        val whole = bytesOf(text("a"), text("b"), text("c"))
        for (cut in 1 until whole.size) {
            val c = open(closeOnFrame = 1)
            c.read(whole.copyOfRange(0, cut))
            c.read(whole.copyOfRange(cut, whole.size))
            assertEquals(listOf("TEXT:a"), c.sink.frames, "split at byte $cut")
            assertEquals(emptyList(), c.sink.errors, "split at byte $cut")
            assertEquals(0, c.tracker.outstandingCount, "every buffer is back (split at $cut)")
        }
    }

    // --- W7: the closing handshake precedes the ending ---

    @Test
    fun `a peer CLOSE followed by its FIN is decoded before the ending`() {
        // Reads are delivered before the read side's closing is, so the
        // CLOSE frame arrives at the sink; what comes after the ending does
        // not.
        val c = open()
        c.read(bytesOf(text("a"), WsFrame.close()))
        c.transport.onReadClosed?.invoke()
        c.read(bytesOf(text("late")))
        assertEquals(listOf("TEXT:a", "CLOSE"), c.sink.frames)
        assertEquals(0, c.tracker.outstandingCount, "every buffer is back")
    }

    // --- W8: the server's frame bridge never sees a frame decoded after the ending ---

    @Test
    fun `a read after the ending never reaches the frame bridge's release path`() {
        // The server pipeline: decoder on the pooled fast path, then the
        // frame bridge that owns undelivered pooled payloads. Frames read
        // before the ending are buffered in the bridge and released by its
        // own ending; a frame read after it would fail `trySend` and take the
        // bridge's release path -- the last resort the decoder now makes
        // unnecessary, so the release count is exactly the buffered frames.
        val tracker = TrackingAllocator(DefaultAllocator)
        val transport = TestIoTransport(tracker)
        val channel = object : AbstractPipelinedChannel(transport, logger) {}
        var released = 0
        val bridge = SuspendMessageBridge(
            WsFrame::class,
            releaseUndelivered = { f: WsFrame ->
                f.inboundPayload?.release()
                released++
            },
        )
        val decoder = WsFrameDecoder(poolDataPayloads = true)
        channel.pipeline.addLast("decoder", decoder)
        channel.pipeline.addLast("bridge", bridge)
        val c = Connection(tracker, transport, channel, Sink(null), decoder)
        c.read(bytesOf(text("a"), text("b")))
        channel.close()
        c.read(bytesOf(text("late")))
        assertEquals(2, released, "only the frames buffered before the ending were released by it")
        assertEquals(
            4,
            tracker.allocateCount,
            "two reads and the two payloads decoded before the ending -- no payload after",
        )
        assertEquals(0, tracker.outstandingCount, "every buffer is back")
    }

    /** Holds every dispatched block until [runQueued]. */
    private class QueueingDispatcher : CoroutineDispatcher() {
        private val queued = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runQueued() {
            while (queued.isNotEmpty()) queued.removeFirst().run()
        }
    }

    private companion object {
        const val MASK: Int = 0x12345678
    }
}
