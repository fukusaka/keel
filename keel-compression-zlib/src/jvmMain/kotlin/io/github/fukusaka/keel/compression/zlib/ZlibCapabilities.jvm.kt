package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.compression.DeflateCapabilities

/**
 * `java.util.zip.Deflater` is fixed at a 32 KiB (15-bit) window — its
 * constructor takes only `level` + `nowrap`, with no `windowBits` knob —
 * so the JVM encoder cannot shrink the window. It does carry the window
 * across messages (`reset` vs full re-init), so context takeover is
 * supported.
 */
internal actual val deflateEncoderCapabilities: DeflateCapabilities =
    DeflateCapabilities(
        windowBits = DeflateCapabilities.FULL_WINDOW_ONLY,
        supportsContextTakeover = true,
    )

/** The JVM `Inflater` decodes any window up to 15 and context-takeover streams. */
internal actual val deflateDecoderCapabilities: DeflateCapabilities =
    DeflateCapabilities(
        windowBits = DeflateCapabilities.FULL_WINDOW_ONLY,
        supportsContextTakeover = true,
    )
