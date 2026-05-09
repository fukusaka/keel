package io.github.fukusaka.keel.testing.websocket

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsFrameDecoder
import io.github.fukusaka.keel.codec.websocket.WsFrameEncoder
import io.github.fukusaka.keel.codec.websocket.parseFrame
import io.github.fukusaka.keel.codec.websocket.writeFrame
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.io.Buffer
import kotlinx.io.readByteArray

/**
 * Shared seam-test infrastructure for the keel WebSocket pipeline.
 *
 * Bundles a [TrackingAllocator] + [TestIoTransport] + [AbstractPipelinedChannel]
 * with the post-upgrade WS pipeline (`WsFrameEncoder` / `WsFrameDecoder` /
 * `WsEchoHandler(postUpgradeMode=true)`) pre-wired, so each test can drive
 * `WsFrame` events directly into [channel] without first staging the HTTP
 * upgrade handshake.
 *
 * Used by per-frame K4 leak detection seam tests (e.g.
 * `NettyPipelineWsEchoSeamTest`) and by large-payload / fragmented seam
 * tests (e.g. `NettyPipelineWsLargePayloadTest`). The full HTTP→WS upgrade
 * flow stays inline in those test classes because it builds its own
 * pipeline (HTTP codec + ws-echo) without `postUpgradeMode`.
 *
 * @property ownsTracker `true` when this context allocated [tracker] itself
 *   (single-channel tests). `false` when the caller passes a shared tracker
 *   across multiple [WsSeamContext] instances (multi-channel tests). The
 *   distinction matters for [assertBalanced]: a context that doesn't own its
 *   tracker must skip the leak assertion, because the multi-channel total is
 *   asserted by the test body after all channels close.
 */
public class WsSeamContext(
    public val tracker: TrackingAllocator,
    public val transport: TestIoTransport,
    public val channel: AbstractPipelinedChannel,
    private val ownsTracker: Boolean,
) {
    /** Encode [frame] to wire bytes and wrap them in a tracker-allocated [IoBuf]. */
    public fun encodeAsIoBuf(frame: WsFrame): IoBuf {
        val bytes = encodeFrame(frame)
        return tracker.allocate(bytes.size).apply { writeByteArray(bytes, 0, bytes.size) }
    }

    /**
     * Decode an outbound capture (`transport.written[i]`) back into a [WsFrame]
     * for assertion. Restores the buffer's reader/writer indexes after the
     * read so subsequent inspection of the same capture is possible — defensive
     * because most tests read each capture once, but multi-pass assertions
     * (decode payload, then verify length, etc.) become correct without an
     * explicit retain.
     */
    public fun decodeOutbound(buf: IoBuf): WsFrame {
        val n = buf.readableBytes
        val bytes = ByteArray(n)
        buf.readByteArray(bytes, 0, n)
        buf.writerIndex = n
        buf.readerIndex = 0
        val scratch = Buffer()
        scratch.write(bytes)
        return parseFrame(scratch)
    }

    /** Release every captured outbound buffer and tear down the channel. */
    public fun close() {
        transport.releaseWritten()
        channel.close()
    }

    /**
     * Assert alloc/release balance — only meaningful when this context owns
     * its tracker. Multi-channel tests share a tracker across contexts and
     * must perform the assertion themselves after closing every channel.
     *
     * Implemented as a raw [AssertionError] throw rather than going through
     * `kotlin.test.assertEquals` to keep this fixture module independent of
     * the kotlin-test runtime — the failure surface (an `AssertionError`
     * with a descriptive message) is the same as what `assertEquals` would
     * produce, but consumers do not need `kotlin-test` on their fixture
     * compile classpath to call this.
     */
    public fun assertBalanced() {
        if (!ownsTracker) return
        if (tracker.outstandingCount != 0) {
            throw AssertionError(
                "IoBuf leak — expected outstandingCount=0, but was ${tracker.outstandingCount} " +
                    "(alloc=${tracker.allocateCount} release=${tracker.releaseCount})",
            )
        }
    }

    public companion object {
        /**
         * Build a [WsSeamContext] in post-upgrade WS state. When [tracker] is
         * `null` (single-channel test), allocates a private tracker; when
         * non-null (multi-channel test), the shared tracker is reused and
         * [assertBalanced] becomes a no-op for this context.
         */
        public fun new(
            tracker: TrackingAllocator? = null,
            label: String = "seam",
        ): WsSeamContext {
            val ownsTracker = tracker == null
            val effectiveTracker = tracker ?: TrackingAllocator(DefaultAllocator)
            val transport = TestIoTransport(effectiveTracker)
            val channel = object : AbstractPipelinedChannel(transport, PrintLogger(label)) {}
            channel.pipeline.addLast("ws-encoder", WsFrameEncoder())
            channel.pipeline.addLast("ws-decoder", WsFrameDecoder())
            channel.pipeline.addLast("ws-echo", WsEchoHandler(postUpgradeMode = true))
            return WsSeamContext(effectiveTracker, transport, channel, ownsTracker)
        }

        /**
         * Encode a [WsFrame] to wire-format bytes via the existing [writeFrame] writer.
         *
         * Uses [readByteArray] (the full-read variant) rather than
         * [kotlinx.io.Source.readAtMostTo], because the latter returns a
         * possibly-short count when the kotlinx-io [Buffer] holds the bytes
         * across multiple internal segments (8 KiB each). At payload sizes
         * ≥8 KiB the partial read silently truncates the encoded frame and
         * the round-trip assertion fails at the segment boundary.
         */
        public fun encodeFrame(frame: WsFrame): ByteArray {
            val scratch = Buffer()
            writeFrame(frame, scratch)
            return scratch.readByteArray(scratch.size.toInt())
        }
    }
}
