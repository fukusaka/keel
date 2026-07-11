package io.github.fukusaka.keel.testing.websocket

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.codec.websocket.WsFrame
import io.github.fukusaka.keel.codec.websocket.WsOpcode
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract self-test for [WsSeamContext] (the pre-wired post-upgrade WS seam
 * infrastructure) and, through it, [WsEchoHandler] (the echo handler it wires).
 * The keel WebSocket seam tests drive frames through this fixture and assert on
 * the echoed output + IoBuf balance, so a silent break in the round-trip wiring,
 * the echo semantics, or the `ownsTracker` leak-assertion gating would weaken
 * all of them.
 *
 * Frames are driven exactly as the real seam tests do —
 * `channel.pipeline.notifyRead(encodeAsIoBuf(frame))` — so the assertions
 * exercise the same encode -> decode -> echo -> encode -> capture path.
 */
class WsSeamContextTest {

    @Test
    fun `a text frame is echoed back unchanged`() {
        val ctx = WsSeamContext.new()
        ctx.channel.pipeline.notifyRead(ctx.encodeAsIoBuf(WsFrame.text("hello", maskKey = CLIENT_MASK)))
        assertEquals(1, ctx.transport.written.size)
        val echoed = ctx.decodeOutbound(ctx.transport.written[0])
        assertEquals(WsOpcode.TEXT, echoed.opcode)
        assertEquals("hello", echoed.payload.decodeToString())
        ctx.close()
        ctx.assertBalanced()
    }

    @Test
    fun `a binary frame is echoed back unchanged`() {
        val ctx = WsSeamContext.new()
        val data = byteArrayOf(1, 2, 3, 4, 5)
        ctx.channel.pipeline.notifyRead(ctx.encodeAsIoBuf(WsFrame.binary(data, maskKey = CLIENT_MASK)))
        val echoed = ctx.decodeOutbound(ctx.transport.written[0])
        assertEquals(WsOpcode.BINARY, echoed.opcode)
        assertContentEquals(data, echoed.payload)
        ctx.close()
        ctx.assertBalanced()
    }

    @Test
    fun `a masked client frame is echoed unmasked`() {
        val ctx = WsSeamContext.new()
        // Client frames are masked; a server MUST NOT mask its frames (RFC 6455
        // 5.1), so the echo drops the mask key.
        ctx.channel.pipeline.notifyRead(ctx.encodeAsIoBuf(WsFrame.text("hi", maskKey = CLIENT_MASK)))
        val echoed = ctx.decodeOutbound(ctx.transport.written[0])
        assertNull(echoed.maskKey)
        assertEquals("hi", echoed.payload.decodeToString())
        ctx.close()
        ctx.assertBalanced()
    }

    @Test
    fun `a PING is answered with a PONG carrying the same payload`() {
        val ctx = WsSeamContext.new()
        val payload = byteArrayOf(9, 8, 7)
        ctx.channel.pipeline.notifyRead(ctx.encodeAsIoBuf(WsFrame.ping(payload)))
        val echoed = ctx.decodeOutbound(ctx.transport.written[0])
        assertEquals(WsOpcode.PONG, echoed.opcode)
        assertContentEquals(payload, echoed.payload)
        ctx.close()
        ctx.assertBalanced()
    }

    @Test
    fun `a CLOSE is echoed and ends echo mode so later frames are dropped`() {
        val ctx = WsSeamContext.new()
        ctx.channel.pipeline.notifyRead(ctx.encodeAsIoBuf(WsFrame.close()))
        assertEquals(1, ctx.transport.written.size)
        assertEquals(WsOpcode.CLOSE, ctx.decodeOutbound(ctx.transport.written[0]).opcode)
        // Echo mode ended with the CLOSE; a subsequent data frame is not echoed.
        ctx.channel.pipeline.notifyRead(ctx.encodeAsIoBuf(WsFrame.text("ignored", maskKey = CLIENT_MASK)))
        assertEquals(1, ctx.transport.written.size)
        ctx.close()
        ctx.assertBalanced()
    }

    @Test
    fun `a payload spanning multiple kotlinx-io segments round-trips intact`() {
        // Guards the encodeFrame full-read vs readAtMostTo bug: a >=8 KiB payload
        // spans multiple 8 KiB segments, and a partial read would truncate it.
        val ctx = WsSeamContext.new()
        val big = ByteArray(20_000) { (it and 0xFF).toByte() }
        ctx.channel.pipeline.notifyRead(ctx.encodeAsIoBuf(WsFrame.binary(big, maskKey = CLIENT_MASK)))
        val echoed = ctx.decodeOutbound(ctx.transport.written[0])
        assertContentEquals(big, echoed.payload)
        ctx.close()
        ctx.assertBalanced()
    }

    @Test
    fun `decodeOutbound restores indexes so the same capture can be read twice`() {
        val ctx = WsSeamContext.new()
        ctx.channel.pipeline.notifyRead(ctx.encodeAsIoBuf(WsFrame.text("twice", maskKey = CLIENT_MASK)))
        val first = ctx.decodeOutbound(ctx.transport.written[0])
        val second = ctx.decodeOutbound(ctx.transport.written[0])
        assertEquals("twice", first.payload.decodeToString())
        assertEquals("twice", second.payload.decodeToString())
        ctx.close()
        ctx.assertBalanced()
    }

    @Test
    fun `assertBalanced fires for an owned tracker but is a no-op for a shared one`() {
        // Owned tracker: assertBalanced must detect an imbalance.
        val owned = WsSeamContext.new()
        val leaked = owned.tracker.allocate(8)
        assertFailsWith<AssertionError> { owned.assertBalanced() }
        leaked.release()
        owned.close()

        // Shared tracker: assertBalanced must be a no-op, because the multi-channel
        // total is asserted by the test body, not per-context.
        val shared = TrackingAllocator(DefaultAllocator)
        val ctx = WsSeamContext.new(tracker = shared)
        val stillOutstanding = shared.allocate(8)
        ctx.assertBalanced() // must not throw despite the outstanding buffer
        assertTrue(shared.outstandingCount > 0)
        stillOutstanding.release()
        ctx.close()
    }

    private companion object {
        /** A real client masks its frames (RFC 6455 5.1); the server decoder
         *  (`requireClientMasking = true`) rejects unmasked data frames. */
        const val CLIENT_MASK: Int = 0x12345678
    }
}
