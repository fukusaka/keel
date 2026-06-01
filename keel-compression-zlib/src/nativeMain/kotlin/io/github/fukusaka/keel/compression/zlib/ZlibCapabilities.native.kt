package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.compression.DeflateCapabilities

/**
 * Native libz (`deflateInit2`) emits a configurable `windowBits` down to
 * 9 and carries the window across messages. The floor is 9, not 8: zlib
 * coerces a requested `windowBits=8` to 9 (a 256-byte window is not
 * supported by `deflate`), so producing a true window-8 stream is
 * impossible — reporting 8 would over-promise.
 */
internal actual val deflateEncoderCapabilities: DeflateCapabilities =
    DeflateCapabilities(
        windowBits = 9..15,
        supportsContextTakeover = true,
    )

/** Native libz `inflateInit2` decodes any 9..15 window and context-takeover streams. */
internal actual val deflateDecoderCapabilities: DeflateCapabilities =
    DeflateCapabilities(
        windowBits = 9..15,
        supportsContextTakeover = true,
    )
