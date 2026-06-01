package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.compression.DeflateCapabilities

/**
 * The JS backend drives Node's one-shot `deflateRawSync` / `gzipSync` per
 * message. It cannot carry the LZ77 window across messages (no context
 * takeover — that needs a persistent `createDeflateRaw` stream), so it
 * reports `supportsContextTakeover = false`.
 *
 * Its `windowBits` option is now forwarded to Node, so the encoder honors
 * a configured window down to 9. The floor is 9, not 8: zlib (which Node
 * wraps) coerces a requested `windowBits=8` to 9, so a true window-8
 * stream cannot be produced and reporting 8 would over-promise.
 */
internal actual val deflateEncoderCapabilities: DeflateCapabilities =
    DeflateCapabilities(
        windowBits = 9..15,
        supportsContextTakeover = false,
    )

/**
 * Node's one-shot inflate cannot follow a context-takeover stream across
 * messages; its full 15-bit window decodes any 9..15 stream a peer sends.
 */
internal actual val deflateDecoderCapabilities: DeflateCapabilities =
    DeflateCapabilities(
        windowBits = 9..15,
        supportsContextTakeover = false,
    )
