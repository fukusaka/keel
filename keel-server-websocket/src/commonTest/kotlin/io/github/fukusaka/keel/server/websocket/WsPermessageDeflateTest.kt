package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.compression.DeflateCapabilities
import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for the `permessage-deflate` engine ([WsPermessageDeflate])
 * and the handshake negotiation ([negotiatePermessageDeflate]).
 *
 * All cases are pure (no I/O, no coroutines) so no timeouts are needed —
 * the streaming compression sessions run synchronously.
 */
/** Smallest DEFLATE window-bits RFC 7692 §7.1.2 permits — nothing below this exists to decline. */
private const val DEFLATE_WINDOW_FLOOR = 8

/** This target's deflate-backend capabilities (native 8..15/ctx; JVM 15..15/ctx; JS 15..15/no-ctx). */
private val deflateEncoderCaps: DeflateCapabilities = DeflateCodec.encoder.capabilities as DeflateCapabilities
private val deflateDecoderCaps: DeflateCapabilities = DeflateCodec.decoder.capabilities as DeflateCapabilities
private val deflateMinWindowBits: Int = deflateEncoderCaps.windowBits.first

class WsPermessageDeflateTest {

    private fun engine(options: WsDeflateOptions = WsDeflateOptions.Default): WsPermessageDeflate =
        WsPermessageDeflate(DeflateCodec, options, serverMaxWindowBits = null, clientMaxWindowBits = null)

    // --- compress / decompress round-trip ---

    @Test
    fun `a compressible payload round-trips through compress and decompress`() {
        val engine = engine()
        try {
            val payload = "permessage-deflate ".repeat(64).encodeToByteArray()
            val result = engine.compress(payload)
            assertTrue(result.compressed, "a large compressible payload must be compressed")
            assertTrue(result.bytes.size < payload.size, "compressed output should be smaller")
            assertContentEquals(payload, engine.decompress(result.bytes))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a payload just below the threshold is sent uncompressed`() {
        val engine = engine(WsDeflateOptions(threshold = 1024))
        try {
            val payload = ByteArray(1023) { 'x'.code.toByte() }
            val result = engine.compress(payload)
            assertFalse(result.compressed, "below-threshold payload must not be compressed")
            assertContentEquals(payload, result.bytes)
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a payload at exactly the threshold is compressed`() {
        val engine = engine(WsDeflateOptions(threshold = 1024))
        try {
            val payload = ByteArray(1024) { 'x'.code.toByte() }
            val result = engine.compress(payload)
            assertTrue(result.compressed, "at-threshold payload must be compressed")
            assertContentEquals(payload, engine.decompress(result.bytes))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `the 00 00 FF FF sync tail is stripped from compressed output`() {
        val engine = engine(WsDeflateOptions(threshold = 0))
        try {
            val payload = "hello world".encodeToByteArray()
            val result = engine.compress(payload)
            // RFC 7692 §7.2.1: the sync tail must not be on the wire.
            val tail = result.bytes.takeLast(4)
            assertFalse(
                tail == listOf<Byte>(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()),
                "the 00 00 FF FF sync tail must be stripped",
            )
            // Decompress re-appends the tail and recovers the payload.
            assertContentEquals(payload, engine.decompress(result.bytes))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `multiple messages round-trip without context takeover`() {
        val engine = engine(WsDeflateOptions(contextTakeover = false, threshold = 0))
        try {
            for (i in 0 until 5) {
                val payload = "message number $i ".repeat(8).encodeToByteArray()
                val result = engine.compress(payload)
                assertContentEquals(payload, engine.decompress(result.bytes))
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `multiple messages round-trip with context takeover`() {
        val encoder = WsPermessageDeflate(
            DeflateCodec,
            WsDeflateOptions(contextTakeover = true, threshold = 0),
            serverMaxWindowBits = null,
            clientMaxWindowBits = null,
        )
        val decoder = WsPermessageDeflate(
            DeflateCodec,
            WsDeflateOptions(contextTakeover = true, threshold = 0),
            serverMaxWindowBits = null,
            clientMaxWindowBits = null,
        )
        try {
            for (i in 0 until 5) {
                val payload = "context takeover message $i ".repeat(8).encodeToByteArray()
                val compressed = encoder.compress(payload)
                assertContentEquals(payload, decoder.decompress(compressed.bytes))
            }
        } finally {
            encoder.close()
            decoder.close()
        }
    }

    @Test
    fun `an empty payload round-trips`() {
        val engine = engine(WsDeflateOptions(threshold = 0))
        try {
            val result = engine.compress(ByteArray(0))
            assertContentEquals(ByteArray(0), engine.decompress(result.bytes))
        } finally {
            engine.close()
        }
    }

    // --- negotiation ---

    @Test
    fun `no extensions header yields no compression`() {
        val result = negotiatePermessageDeflate(null, DeflateCodec, WsDeflateOptions.Default)
        assertIs<WsExtensionResult.None>(result)
    }

    @Test
    fun `a non-deflate offer yields no compression`() {
        val result = negotiatePermessageDeflate("x-some-extension", DeflateCodec, WsDeflateOptions.Default)
        assertIs<WsExtensionResult.None>(result)
    }

    @Test
    fun `a null codec yields no compression even with a deflate offer`() {
        val result = negotiatePermessageDeflate("permessage-deflate", null, WsDeflateOptions.Default)
        assertIs<WsExtensionResult.None>(result)
    }

    @Test
    fun `a bare deflate offer is accepted with no-context-takeover by default`() {
        val result = negotiatePermessageDeflate("permessage-deflate", DeflateCodec, WsDeflateOptions.Default)
        val deflate = assertIs<WsExtensionResult.Deflate>(result)
        assertTrue(deflate.responseHeaderValue.contains("permessage-deflate"))
        // keel default: no context takeover → both params present.
        assertTrue(deflate.responseHeaderValue.contains("server_no_context_takeover"))
        assertTrue(deflate.responseHeaderValue.contains("client_no_context_takeover"))
        assertFalse(deflate.effectiveOptions.contextTakeover)
    }

    @Test
    fun `server_max_window_bits is honored or declined per backend capability`() {
        // A backend that can shrink its window (native libz, minWindowBits=8)
        // agrees to and echoes server_max_window_bits=12; one that is fixed at
        // 15 (JVM Deflater / JS one-shot) must decline rather than over-promise
        // a window it cannot produce.
        val minBits = deflateMinWindowBits
        val result = negotiatePermessageDeflate(
            "permessage-deflate; server_max_window_bits=12",
            DeflateCodec,
            WsDeflateOptions.Default,
        )
        if (minBits <= 12) {
            val deflate = assertIs<WsExtensionResult.Deflate>(result)
            assertEquals(12, deflate.serverMaxWindowBits)
            assertTrue(deflate.responseHeaderValue.contains("server_max_window_bits=12"))
        } else {
            assertIs<WsExtensionResult.None>(result)
        }
    }

    @Test
    fun `server_max_window_bits=15 is always honored`() {
        // 15 is the largest legal window, so every backend (minWindowBits <= 15)
        // can produce it — the offer is accepted regardless of platform.
        val result = negotiatePermessageDeflate(
            "permessage-deflate; server_max_window_bits=15",
            DeflateCodec,
            WsDeflateOptions.Default,
        )
        val deflate = assertIs<WsExtensionResult.Deflate>(result)
        assertEquals(15, deflate.serverMaxWindowBits)
        assertTrue(deflate.responseHeaderValue.contains("server_max_window_bits=15"))
    }

    @Test
    fun `server_max_window_bits=8 is declined because zlib coerces 8 to 9`() {
        // zlib (native libz / Node) coerces a requested windowBits=8 to 9 — a
        // 256-byte window is not supported by deflate — so no zlib backend can
        // produce a true window-8 stream. Echoing 8 while emitting window-9
        // would over-promise (RFC 7692 §7.1.2.1), so the offer is declined on
        // every backend (the floor is 9).
        val result = negotiatePermessageDeflate(
            "permessage-deflate; server_max_window_bits=8",
            DeflateCodec,
            WsDeflateOptions.Default,
        )
        assertIs<WsExtensionResult.None>(result)
    }

    @Test
    fun `a server_max_window_bits below the backend minimum is never over-promised`() {
        // Regression for the over-promise bug: a fixed-15 backend used to echo
        // server_max_window_bits=N (N<15) it could not honor, corrupting the
        // client's inflater. It must now decline the offer instead.
        val minBits = deflateMinWindowBits
        if (minBits <= DEFLATE_WINDOW_FLOOR) return // backend honors all; nothing below to test
        val below = minBits - 1
        val result = negotiatePermessageDeflate(
            "permessage-deflate; server_max_window_bits=$below",
            DeflateCodec,
            WsDeflateOptions.Default,
        )
        assertIs<WsExtensionResult.None>(result)
    }

    @Test
    fun `an unhonorable server_max_window_bits offer falls back to a later bare offer`() {
        val minBits = deflateMinWindowBits
        if (minBits <= DEFLATE_WINDOW_FLOOR) return // no decline path on a full-feature backend
        // First offer asks for a window the backend can't produce → declined;
        // the bare second offer is accepted with no server_max_window_bits.
        val result = negotiatePermessageDeflate(
            "permessage-deflate; server_max_window_bits=${minBits - 1}, permessage-deflate",
            DeflateCodec,
            WsDeflateOptions.Default,
        )
        val deflate = assertIs<WsExtensionResult.Deflate>(result)
        assertFalse(deflate.responseHeaderValue.contains("server_max_window_bits"))
    }

    @Test
    fun `server context takeover is honored or forced off per backend capability`() {
        // With context takeover requested, a backend that supports it (native
        // libz / JVM Deflater) keeps the window across messages — no
        // server_no_context_takeover. One that cannot (JS one-shot) must force
        // it off rather than over-promise a takeover the encoder cannot honor.
        val result = negotiatePermessageDeflate(
            "permessage-deflate",
            DeflateCodec,
            WsDeflateOptions(contextTakeover = true),
        )
        val deflate = assertIs<WsExtensionResult.Deflate>(result)
        if (deflateEncoderCaps.supportsContextTakeover) {
            assertFalse(deflate.responseHeaderValue.contains("server_no_context_takeover"))
            assertTrue(deflate.effectiveOptions.contextTakeover)
        } else {
            assertTrue(deflate.responseHeaderValue.contains("server_no_context_takeover"))
            assertFalse(deflate.effectiveOptions.contextTakeover)
        }
    }

    @Test
    fun `client context takeover is forced off when the server decoder cannot follow it`() {
        // The server's decoder must follow a client that keeps its window across
        // messages; a JS one-shot decoder cannot, so client_no_context_takeover
        // is forced rather than accepting a stream it cannot decode.
        val result = negotiatePermessageDeflate(
            "permessage-deflate",
            DeflateCodec,
            WsDeflateOptions(contextTakeover = true),
        )
        val deflate = assertIs<WsExtensionResult.Deflate>(result)
        if (deflateDecoderCaps.supportsContextTakeover) {
            assertFalse(deflate.responseHeaderValue.contains("client_no_context_takeover"))
        } else {
            assertTrue(deflate.responseHeaderValue.contains("client_no_context_takeover"))
        }
    }

    @Test
    fun `client_max_window_bits offered with a value is honored`() {
        val result = negotiatePermessageDeflate(
            "permessage-deflate; client_max_window_bits=10",
            DeflateCodec,
            WsDeflateOptions.Default,
        )
        val deflate = assertIs<WsExtensionResult.Deflate>(result)
        assertEquals(10, deflate.clientMaxWindowBits)
        assertTrue(deflate.responseHeaderValue.contains("client_max_window_bits=10"))
    }

    @Test
    fun `client_max_window_bits offered valueless echoes the maximum`() {
        val result = negotiatePermessageDeflate(
            "permessage-deflate; client_max_window_bits",
            DeflateCodec,
            WsDeflateOptions.Default,
        )
        val deflate = assertIs<WsExtensionResult.Deflate>(result)
        assertEquals(15, deflate.clientMaxWindowBits)
    }

    @Test
    fun `an out-of-range window-bits value declines the offer`() {
        val result = negotiatePermessageDeflate(
            "permessage-deflate; server_max_window_bits=99",
            DeflateCodec,
            WsDeflateOptions.Default,
        )
        assertIs<WsExtensionResult.None>(result)
    }

    @Test
    fun `an unknown parameter declines the offer`() {
        val result = negotiatePermessageDeflate(
            "permessage-deflate; bogus_param",
            DeflateCodec,
            WsDeflateOptions.Default,
        )
        assertIs<WsExtensionResult.None>(result)
    }

    @Test
    fun `the first valid deflate offer of several is accepted`() {
        // server_max_window_bits=15 is honorable on every backend, so the
        // assertion is deterministic across platforms.
        val result = negotiatePermessageDeflate(
            "permessage-deflate; bogus_param, permessage-deflate; server_max_window_bits=15",
            DeflateCodec,
            WsDeflateOptions.Default,
        )
        val deflate = assertIs<WsExtensionResult.Deflate>(result)
        assertEquals(15, deflate.serverMaxWindowBits)
    }
}
