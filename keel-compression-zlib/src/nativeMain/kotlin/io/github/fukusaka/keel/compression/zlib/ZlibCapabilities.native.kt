package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.compression.DeflateCapabilities

/**
 * Native libz (`deflateInit2`) emits a configurable `windowBits` down to
 * 8 and carries the window across messages, so it has no irreducible
 * limit on either axis.
 */
internal actual val deflateEncoderCapabilities: DeflateCapabilities =
    DeflateCapabilities(
        windowBits = 8..15,
        supportsContextTakeover = true,
    )

/** Native libz `inflateInit2` decodes any 8..15 window and context-takeover streams. */
internal actual val deflateDecoderCapabilities: DeflateCapabilities =
    DeflateCapabilities(
        windowBits = 8..15,
        supportsContextTakeover = true,
    )
