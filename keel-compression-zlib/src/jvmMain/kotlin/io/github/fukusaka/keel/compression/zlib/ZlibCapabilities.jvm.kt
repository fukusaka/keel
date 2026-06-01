package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.compression.DeflateCapabilities
import io.github.fukusaka.keel.compression.Strategy

/**
 * `java.util.zip.Deflater` is fixed at a 32 KiB (15-bit) window — its
 * constructor takes only `level` + `nowrap`, with no `windowBits` knob —
 * so the JVM encoder cannot shrink the window. It does carry the window
 * across messages (`reset` vs full re-init), so context takeover is
 * supported.
 *
 * `Deflater` only exposes `DEFAULT_STRATEGY` / `FILTERED` / `HUFFMAN_ONLY`
 * — it has no `Z_RLE` / `Z_FIXED` constant — so [Strategy.RunLength] and
 * [Strategy.Fixed] are unsupported and are coerced to [Strategy.Default].
 */
internal actual val deflateEncoderCapabilities: DeflateCapabilities =
    DeflateCapabilities(
        windowBits = DeflateCapabilities.FULL_WINDOW_ONLY,
        supportsContextTakeover = true,
        supportedStrategies = setOf(Strategy.Default, Strategy.Filtered, Strategy.HuffmanOnly),
    )

/** The JVM `Inflater` decodes any window up to 15 and context-takeover streams. */
internal actual val deflateDecoderCapabilities: DeflateCapabilities =
    DeflateCapabilities(
        windowBits = DeflateCapabilities.FULL_WINDOW_ONLY,
        supportsContextTakeover = true,
    )
