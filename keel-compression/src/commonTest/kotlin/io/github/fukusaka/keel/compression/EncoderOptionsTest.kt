package io.github.fukusaka.keel.compression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pin the documented defaults for [EncoderOptions] / [DecoderOptions].
 *
 * Backend implementations branch on these defaults (e.g. zlib's
 * `defaultWrap` only kicks in when `wrapFormat == WrapFormat.Default`);
 * a silent change to the default values would alter on-wire behaviour
 * across every backend.
 */
class EncoderOptionsTest {

    @Test
    fun `EncoderOptions default level is backend-default sentinel`() {
        // -1 = "backend default" per KDoc (zlib Z_DEFAULT_COMPRESSION,
        // brotli quality 6, etc.).
        assertEquals(-1, EncoderOptions.Default.level)
    }

    @Test
    fun `EncoderOptions default wrapFormat is Default`() {
        assertEquals(WrapFormat.Default, EncoderOptions.Default.wrapFormat)
    }

    @Test
    fun `EncoderOptions default flushMode is Sync`() {
        // Sync matches HTTP chunked streaming + WS permessage-deflate;
        // changing this default would break WS framing.
        assertEquals(FlushMode.Sync, EncoderOptions.Default.flushMode)
    }

    @Test
    fun `EncoderOptions default contextTakeover is true`() {
        // HTTP response / browser default; gRPC + WS *_no_context_takeover
        // explicitly opt out.
        assertTrue(EncoderOptions.Default.contextTakeover)
    }

    @Test
    fun `EncoderOptions default dictionary is null`() {
        assertNull(EncoderOptions.Default.dictionary)
    }

    @Test
    fun `EncoderOptions default tuning is null`() {
        assertNull(EncoderOptions.Default.tuning)
    }

    @Test
    fun `DeflateTuning defaults to null windowBits and default strategy`() {
        val tuning = DeflateTuning()
        assertNull(tuning.windowBits)
        assertEquals(Strategy.Default, tuning.strategy)
    }

    @Test
    fun `EncoderOptions Default is a singleton`() {
        assertSame(EncoderOptions.Default, EncoderOptions.Default)
    }

    @Test
    fun `DecoderOptions default wrapFormat is Default`() {
        assertEquals(WrapFormat.Default, DecoderOptions.Default.wrapFormat)
    }

    @Test
    fun `DecoderOptions default tuning is null`() {
        assertNull(DecoderOptions.Default.tuning)
    }

    @Test
    fun `DecoderOptions default dictionary is null`() {
        assertNull(DecoderOptions.Default.dictionary)
    }

    @Test
    fun `DecoderOptions default contextTakeover is true`() {
        assertTrue(DecoderOptions.Default.contextTakeover)
    }

    @Test
    fun `DecoderOptions default maxOutputSize is null`() {
        // null = unlimited per KDoc. HTTP clients are expected to set
        // this from their resource budget.
        assertNull(DecoderOptions.Default.maxOutputSize)
    }

    @Test
    fun `DecoderOptions default maxRatio is null`() {
        assertNull(DecoderOptions.Default.maxRatio)
    }

    @Test
    fun `DecoderOptions Default is a singleton`() {
        assertSame(DecoderOptions.Default, DecoderOptions.Default)
    }

    @Test
    fun `EncoderOptions constructor accepts explicit values`() {
        val dict = ByteArray(8) { it.toByte() }
        val opts = EncoderOptions(
            level = 9,
            wrapFormat = WrapFormat.Raw,
            flushMode = FlushMode.NoFlush,
            contextTakeover = false,
            dictionary = dict,
            tuning = DeflateTuning(windowBits = -15, strategy = Strategy.HuffmanOnly),
        )
        assertEquals(9, opts.level)
        assertEquals(WrapFormat.Raw, opts.wrapFormat)
        assertEquals(FlushMode.NoFlush, opts.flushMode)
        assertEquals(false, opts.contextTakeover)
        assertSame(dict, opts.dictionary)
        val tuning = opts.tuning as DeflateTuning
        assertEquals(-15, tuning.windowBits)
        assertEquals(Strategy.HuffmanOnly, tuning.strategy)
    }

    @Test
    fun `DecoderOptions constructor accepts explicit values`() {
        val dict = ByteArray(4) { it.toByte() }
        val opts = DecoderOptions(
            wrapFormat = WrapFormat.Gzip,
            dictionary = dict,
            contextTakeover = false,
            maxOutputSize = 1024L,
            maxRatio = 100,
            tuning = DeflateTuning(windowBits = 15),
        )
        assertEquals(WrapFormat.Gzip, opts.wrapFormat)
        assertEquals(15, (opts.tuning as DeflateTuning).windowBits)
        assertSame(dict, opts.dictionary)
        assertEquals(false, opts.contextTakeover)
        assertEquals(1024L, opts.maxOutputSize)
        assertEquals(100, opts.maxRatio)
    }

    @Test
    fun `WrapFormat enum values are stable`() {
        // Pin the names — backends switch on them; a rename would be a
        // BREAKING change.
        val names = WrapFormat.entries.map { it.name }.toSet()
        assertEquals(setOf("Default", "Gzip", "Zlib", "Raw"), names)
    }

    @Test
    fun `FlushMode enum values are stable`() {
        val names = FlushMode.entries.map { it.name }.toSet()
        assertEquals(setOf("NoFlush", "Sync", "Full", "Block"), names)
    }

    @Test
    fun `Strategy enum values are stable`() {
        val names = Strategy.entries.map { it.name }.toSet()
        assertEquals(setOf("Default", "Filtered", "HuffmanOnly", "RunLength", "Fixed"), names)
    }

    @Test
    fun `CodecStatus enum values are stable`() {
        // Three values exactly — this is the SPI state-machine alphabet.
        val names = CodecStatus.entries.map { it.name }.toSet()
        assertEquals(setOf("NEED_OUTPUT", "NEED_INPUT", "FINISHED"), names)
    }

    @Test
    fun `Default options reference is non-null`() {
        // Sanity guard against `EncoderOptions.Default = null` regressions
        // in companion-object init order.
        assertNotNull(EncoderOptions.Default)
        assertNotNull(DecoderOptions.Default)
    }
}
