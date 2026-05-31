package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.compression.DeflateCapabilities

/**
 * The JS backend drives Node's one-shot `deflateRawSync` / `gzipSync` per
 * message. It cannot carry the LZ77 window across messages (no context
 * takeover — that needs a persistent `createDeflateRaw` stream), and
 * until its `windowBits` option is wired it emits the full 15-bit window,
 * so it reports `15..15`. (Node does support an 8..15 `windowBits`
 * option; widening this range is the wiring follow-up.)
 */
internal actual val deflateEncoderCapabilities: DeflateCapabilities =
    DeflateCapabilities(
        windowBits = DeflateCapabilities.FULL_WINDOW_ONLY,
        supportsContextTakeover = false,
    )

/** Node's one-shot inflate cannot follow a context-takeover stream across messages. */
internal actual val deflateDecoderCapabilities: DeflateCapabilities =
    DeflateCapabilities(
        windowBits = DeflateCapabilities.FULL_WINDOW_ONLY,
        supportsContextTakeover = false,
    )
