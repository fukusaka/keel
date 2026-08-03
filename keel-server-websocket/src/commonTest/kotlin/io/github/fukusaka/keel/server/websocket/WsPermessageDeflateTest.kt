package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.CompressionCapabilities
import io.github.fukusaka.keel.compression.CompressionCodec
import io.github.fukusaka.keel.compression.Decoder
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.DeflateCapabilities
import io.github.fukusaka.keel.compression.Encoder
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.Strategy
import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import io.github.fukusaka.keel.testing.InjectedFault
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        WsPermessageDeflate(
            allocator = DefaultAllocator,
            codec = DeflateCodec,
            options = options,
            serverMaxWindowBits = null,
            clientMaxWindowBits = null,
        )

    // --- compress / decompress round-trip ---

    @Test
    fun `the configured deflate strategy is honored end-to-end`() {
        // HuffmanOnly disables LZ77 string matching, so repetitive data compresses
        // far worse than the default. A larger HuffmanOnly output proves the
        // `deflate { strategy = … }` DSL knob flows through WsDeflateOptions to the
        // encoder's DeflateTuning.
        val payload = "ABCD".repeat(500).encodeToByteArray()
        fun compressedSize(strategy: Strategy): Int {
            val engine = engine(WsDeflateOptions(threshold = 0, strategy = strategy))
            try {
                return wireBytes(engine.compress(payload)).size
            } finally {
                engine.close()
            }
        }
        val default = compressedSize(Strategy.Default)
        val huffman = compressedSize(Strategy.HuffmanOnly)
        assertTrue(huffman > default, "HuffmanOnly ($huffman B) should exceed default ($default B)")
    }

    @Test
    fun `a compressible payload round-trips through compress and decompress`() {
        val engine = engine()
        try {
            val payload = "permessage-deflate ".repeat(64).encodeToByteArray()
            val result = engine.compress(payload)
            assertTrue(result.compressed, "a large compressible payload must be compressed")
            val bytes = wireBytes(result)
            assertTrue(bytes.size < payload.size, "compressed output should be smaller")
            assertContentEquals(payload, engine.decompress(bytes))
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
            assertContentEquals(payload, wireBytes(result))
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
            assertContentEquals(payload, engine.decompress(wireBytes(result)))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `the 00 00 FF FF sync tail is stripped from compressed output`() {
        val engine = engine(WsDeflateOptions(threshold = 0))
        try {
            val payload = "hello world".encodeToByteArray()
            val bytes = wireBytes(engine.compress(payload))
            // RFC 7692 §7.2.1: the sync tail must not be on the wire.
            val tail = bytes.takeLast(4)
            assertFalse(
                tail == listOf<Byte>(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte()),
                "the 00 00 FF FF sync tail must be stripped",
            )
            // Decompress re-appends the tail and recovers the payload.
            assertContentEquals(payload, engine.decompress(bytes))
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
                val bytes = wireBytes(engine.compress(payload))
                assertContentEquals(payload, engine.decompress(bytes))
            }
        } finally {
            engine.close()
        }
    }

    @Test
    fun `multiple messages round-trip with context takeover`() {
        val encoder = WsPermessageDeflate(
            allocator = DefaultAllocator,
            codec = DeflateCodec,
            options = WsDeflateOptions(contextTakeover = true, threshold = 0),
            serverMaxWindowBits = null,
            clientMaxWindowBits = null,
        )
        val decoder = WsPermessageDeflate(
            allocator = DefaultAllocator,
            codec = DeflateCodec,
            options = WsDeflateOptions(contextTakeover = true, threshold = 0),
            serverMaxWindowBits = null,
            clientMaxWindowBits = null,
        )
        try {
            for (i in 0 until 5) {
                val payload = "context takeover message $i ".repeat(8).encodeToByteArray()
                val bytes = wireBytes(encoder.compress(payload))
                assertContentEquals(payload, decoder.decompress(bytes))
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
            val bytes = wireBytes(engine.compress(ByteArray(0)))
            assertContentEquals(ByteArray(0), engine.decompress(bytes))
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a large payload spanning multiple codec output chunks round-trips`() {
        // The encoder drains its output into a fresh pooled chunk per step and
        // the decoder grows its byte sink; a payload several chunks long
        // exercises both the multi-chunk accumulation and the sink's capacity
        // doubling. Pseudo-random bytes barely compress, so the deflated stream
        // also spans multiple chunks (it also guards against the flush-framed
        // truncation fixed in #666 — a message compressing past one buffer).
        val engine = engine(WsDeflateOptions(threshold = 0))
        try {
            val payload = ByteArray(64 * 1024).also { kotlin.random.Random(seed = 42).nextBytes(it) }
            val bytes = wireBytes(engine.compress(payload))
            assertContentEquals(payload, engine.decompress(bytes))
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
    fun `asymmetric server_no_context_takeover offer keeps client-side decoder takeover on`() {
        // RFC 7692 negotiates the two takeover directions independently. A
        // client offering only `server_no_context_takeover` (forcing the
        // server's encoder to reset its window every message while keeping
        // its own client-side takeover) used to collapse into a single
        // boolean in `effectiveOptions.contextTakeover` and propagate down
        // to BOTH the encoder and the decoder. The server's decoder then
        // reset its window between messages, and the next inbound message
        // that LZ77-back-referenced the old window surfaced `Z_DATA_ERROR`
        // ("invalid distance too far back").
        //
        // Backends that cannot honour takeover (JS one-shot zlib) force both
        // flags off regardless of the offer; the asymmetric-decoupling
        // invariant only has bite when the backend can actually keep windows.
        if (!deflateEncoderCaps.supportsContextTakeover || !deflateDecoderCaps.supportsContextTakeover) return
        val result = negotiatePermessageDeflate(
            "permessage-deflate; server_no_context_takeover",
            DeflateCodec,
            WsDeflateOptions(contextTakeover = true),
        )
        val deflate = assertIs<WsExtensionResult.Deflate>(result)
        assertFalse(
            deflate.effectiveServerContextTakeover,
            "server-side encoder must honour the client's `server_no_context_takeover` offer",
        )
        assertTrue(
            deflate.effectiveClientContextTakeover,
            "client-side decoder must keep takeover on when the client did not opt out",
        )
        assertTrue(deflate.responseHeaderValue.contains("server_no_context_takeover"))
        assertFalse(deflate.responseHeaderValue.contains("client_no_context_takeover"))
    }

    @Test
    fun `asymmetric client_no_context_takeover offer keeps server-side encoder takeover on`() {
        // Mirror of the test above: client opts out of its own takeover
        // while letting the server keep its window.
        if (!deflateEncoderCaps.supportsContextTakeover || !deflateDecoderCaps.supportsContextTakeover) return
        val result = negotiatePermessageDeflate(
            "permessage-deflate; client_no_context_takeover",
            DeflateCodec,
            WsDeflateOptions(contextTakeover = true),
        )
        val deflate = assertIs<WsExtensionResult.Deflate>(result)
        assertTrue(
            deflate.effectiveServerContextTakeover,
            "server-side encoder must keep takeover on when the server did not opt out",
        )
        assertFalse(
            deflate.effectiveClientContextTakeover,
            "server-side decoder must honour the client's `client_no_context_takeover` offer",
        )
        assertFalse(deflate.responseHeaderValue.contains("server_no_context_takeover"))
        assertTrue(deflate.responseHeaderValue.contains("client_no_context_takeover"))
    }

    @Test
    fun `negotiation preserves the configured strategy in the effective options`() {
        // The negotiated effectiveOptions are what reaches WsPermessageDeflate, so
        // a strategy set in the deflate DSL must survive negotiation (it rebuilds
        // WsDeflateOptions). Dropping it would silently force Default on the wire.
        val result = negotiatePermessageDeflate(
            "permessage-deflate",
            DeflateCodec,
            WsDeflateOptions(strategy = Strategy.HuffmanOnly),
        )
        val deflate = assertIs<WsExtensionResult.Deflate>(result)
        assertEquals(Strategy.HuffmanOnly, deflate.effectiveOptions.strategy)
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

    // --- allocator borrow contract ---

    @Test
    fun `close releases encoder and decoder but does NOT close the borrowed allocator`() {
        // Pre-fix the engine constructed `defaultAllocator()` internally
        // and never closed it — every WS-deflate connection leaked a
        // fresh pool allocator for the engine's lifetime. The fix is to
        // borrow the channel's per-EL allocator instead. This test pins
        // the borrow side of the contract: a TrackingAllocator passed
        // in must NOT see close() during the engine's own close().
        val tracker = io.github.fukusaka.keel.buf.TrackingAllocator(DefaultAllocator)
        val engine = WsPermessageDeflate(
            allocator = tracker,
            codec = DeflateCodec,
            options = WsDeflateOptions.Default,
            serverMaxWindowBits = null,
            clientMaxWindowBits = null,
        )
        engine.close()
        assertEquals(
            0,
            tracker.closeCount,
            "WsPermessageDeflate.close() must NOT close the borrowed allocator",
        )
    }

    // --- close idempotency (4th deep-review S9, gates the M3 upgrade leak fix) ---

    @Test
    fun `close is idempotent and does not call backend close twice`() {
        // M3 fix wraps the WebSocketUpgrade pre-session region in a
        // try / catch that closes the deflate engine on a pipeline mutation
        // throw — but the existing post-handler `releaseDeflate` in the
        // inner finally already closes it on the success path. With both
        // paths potentially running, the engine's `close()` is called
        // twice in the upgrade-then-immediate-failure window, so the
        // engine must guard against a double close instead of relying on
        // every backend honouring the SPI's per-session idempotency.
        //
        // Red-Green: a counting codec records each `close()` it receives.
        // Pre-fix `WsPermessageDeflate.close()` is unguarded — a second
        // call reaches both encoder and decoder, the counter reports 2.
        // Post-fix the `closed` guard skips the second call, the counter
        // stays at 1.
        val codec = CountingCloseCodec(DeflateCodec)
        val engine = WsPermessageDeflate(
            allocator = DefaultAllocator,
            codec = codec,
            options = WsDeflateOptions.Default,
            serverMaxWindowBits = null,
            clientMaxWindowBits = null,
        )
        engine.close()
        engine.close()
        assertEquals(1, codec.encoderCloseCalls, "encoder.close must run exactly once across two engine.close()")
        assertEquals(1, codec.decoderCloseCalls, "decoder.close must run exactly once across two engine.close()")
    }

    // --- encoder-fault poison (4th deep-review S-D3) ---

    @Test
    fun `an encoder fault poisons the engine so the next compress fails fast`() {
        // S-D3: when the encoder throws mid-message the DEFLATE stream is in
        // an undefined state (context takeover carries the window across
        // messages). Industry norm (Netty / ws / gorilla / tungstenite) is to
        // treat a codec fault as fatal — never reset-and-continue. The engine
        // poisons itself so a caller that catches the failed send and retries
        // does not run the dirty encoder (which could emit a corrupt DEFLATE
        // stream the peer silently rejects).
        //
        // Red-Green: the encoder throws on every update. Pre-fix the second
        // compress() re-runs the dirty encoder and throws the backend's
        // RuntimeException again (not IllegalStateException). Post-fix the
        // `encoderBroken` guard makes the second compress() fail fast with
        // IllegalStateException without touching the encoder.
        val engine = WsPermessageDeflate(
            allocator = DefaultAllocator,
            codec = ThrowOnEncodeCodec(DeflateCodec),
            options = WsDeflateOptions(threshold = 0),
            serverMaxWindowBits = null,
            clientMaxWindowBits = null,
        )
        val payload = ByteArray(64) { it.toByte() }
        try {
            // First compress drives the encoder, which throws — poisons. The
            // injected type, not RuntimeException: IllegalStateException is one too,
            // so the generic form could not tell the pre-fix path from the post-fix
            // one this test exists to distinguish.
            assertFailsWith<InjectedFault> { engine.compress(payload) }
            // Second compress must fail fast with the poison, not re-run the encoder.
            val ex = assertFailsWith<IllegalStateException> { engine.compress(payload) }
            assertTrue(
                ex.message?.contains("broken") == true,
                "expected the encoder-broken poison message, got: ${ex.message}",
            )
        } finally {
            engine.close()
        }
    }

    /**
     * Delegating [CompressionCodec] whose encoder's `update` always throws,
     * simulating a mid-message backend fault. The decoder side delegates
     * normally (unused by the encoder-fault test).
     */
    private class ThrowOnEncodeCodec(private val inner: CompressionCodec) : CompressionCodec {
        override val name: String = inner.name
        override val encoder: Encoder = object : Encoder {
            override val name: String = inner.encoder.name
            override val capabilities: CompressionCapabilities? = inner.encoder.capabilities
            override fun newSession(allocator: BufferAllocator, options: EncoderOptions): EncoderSession {
                val delegate = inner.encoder.newSession(allocator, options)
                return object : EncoderSession {
                    override fun update(input: IoBuf, output: IoBuf): CodecStatus =
                        throw InjectedFault("simulated encoder backend fault")
                    override fun flush(output: IoBuf): CodecStatus = delegate.flush(output)
                    override fun finish(output: IoBuf): CodecStatus = delegate.finish(output)
                    override fun reset() = delegate.reset()
                    override fun close() = delegate.close()
                }
            }
        }
        override val decoder: Decoder = inner.decoder
    }

    /**
     * Delegating [CompressionCodec] that records how many times each side's
     * `close()` is invoked. Wraps an inner real codec so the streaming
     * encode / decode paths still produce valid bytes — only the close
     * counter is observed.
     */
    private class CountingCloseCodec(private val inner: CompressionCodec) : CompressionCodec {
        override val name: String = inner.name
        var encoderCloseCalls: Int = 0
            private set
        var decoderCloseCalls: Int = 0
            private set
        override val encoder: Encoder = object : Encoder {
            override val name: String = inner.encoder.name
            override val capabilities: CompressionCapabilities? = inner.encoder.capabilities
            override fun newSession(allocator: BufferAllocator, options: EncoderOptions): EncoderSession {
                val delegate = inner.encoder.newSession(allocator, options)
                return object : EncoderSession {
                    override fun update(input: IoBuf, output: IoBuf): CodecStatus = delegate.update(input, output)
                    override fun flush(output: IoBuf): CodecStatus = delegate.flush(output)
                    override fun finish(output: IoBuf): CodecStatus = delegate.finish(output)
                    override fun reset() = delegate.reset()
                    override fun close() {
                        encoderCloseCalls++
                        delegate.close()
                    }
                }
            }
        }
        override val decoder: Decoder = object : Decoder {
            override val name: String = inner.decoder.name
            override val capabilities: CompressionCapabilities? = inner.decoder.capabilities
            override fun newSession(allocator: BufferAllocator, options: DecoderOptions): DecoderSession {
                val delegate = inner.decoder.newSession(allocator, options)
                return object : DecoderSession {
                    override fun update(input: IoBuf, output: IoBuf): CodecStatus = delegate.update(input, output)
                    override fun flush(output: IoBuf): CodecStatus = delegate.flush(output)
                    override fun finish(output: IoBuf): CodecStatus = delegate.finish(output)
                    override fun reset() = delegate.reset()
                    override fun close() {
                        decoderCloseCalls++
                        delegate.close()
                    }
                }
            }
        }
    }
}
