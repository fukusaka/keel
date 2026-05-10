package io.github.fukusaka.keel.compression

/**
 * Per-session encoder configuration.
 *
 * Backend implementations honour the subset of fields they understand —
 * e.g. `windowBits` and `strategy` only apply to zlib-family backends,
 * brotli ignores them. Backends document which fields they read.
 *
 * @property level compression level. Backend-specific scale; `-1` =
 *   backend default (zlib `Z_DEFAULT_COMPRESSION`, brotli quality 6, etc.)
 * @property wrapFormat output framing. [WrapFormat.Default] lets the
 *   backend pick its convention; explicit values force a specific
 *   wrapper (`Gzip` = RFC 1952 header + CRC32 trailer, `Zlib` = RFC
 *   1950 wrapper, `Raw` = no wrapper, used by WebSocket
 *   permessage-deflate `windowBits = -15`)
 * @property flushMode controls when buffered bytes become byte-aligned
 *   output. [FlushMode.Sync] is the default and matches HTTP streaming
 *   plus WebSocket `permessage-deflate` (which requires `Z_SYNC_FLUSH`
 *   so the 4-byte tail `00 00 ff ff` is emitted at frame boundaries)
 * @property contextTakeover whether internal compression state
 *   carries across [EncoderSession.reset] boundaries. Default `true`
 *   (HTTP response, browser); `false` is required for gRPC per-message
 *   and for WebSocket extensions that negotiate
 *   `server_no_context_takeover` / `client_no_context_takeover`
 * @property dictionary optional pre-shared dictionary bytes for
 *   `deflate` / `brotli` / `zstd`. Significantly improves compression
 *   ratio for short messages (gRPC, WS small frames). Both encoder and
 *   decoder must use the same dictionary
 * @property windowBits explicit window bits override (zlib family only).
 *   `-15..-8` = raw deflate, `8..15` = zlib wrapper, `+16` over zlib =
 *   gzip wrapper. Most callers should use [wrapFormat] instead;
 *   [windowBits] is provided for protocols that need a specific value
 *   (WebSocket negotiates `client_max_window_bits` / `server_max_window_bits`)
 * @property strategy zlib compression strategy hint. Low-priority;
 *   most callers leave it at [Strategy.Default]
 */
public class EncoderOptions(
    public val level: Int = -1,
    public val wrapFormat: WrapFormat = WrapFormat.Default,
    public val flushMode: FlushMode = FlushMode.Sync,
    public val contextTakeover: Boolean = true,
    public val dictionary: ByteArray? = null,
    public val windowBits: Int? = null,
    public val strategy: Strategy = Strategy.Default,
) {
    public companion object {
        public val Default: EncoderOptions = EncoderOptions()
    }
}

/**
 * Per-session decoder configuration.
 *
 * @property wrapFormat expected framing of input bytes. [WrapFormat.Default]
 *   lets the decoder auto-detect when the backend supports it (zlib's
 *   `inflateInit2` with `windowBits = 47` does gzip + zlib auto-detect)
 * @property windowBits explicit window bits override (zlib family only).
 *   See [EncoderOptions.windowBits]
 * @property dictionary optional pre-shared dictionary bytes. Must match
 *   the dictionary used during encoding
 * @property contextTakeover whether internal decompression state
 *   carries across [DecoderSession.reset] boundaries
 * @property maxOutputSize hard cap on total decoded output bytes per
 *   session (across all [DecoderSession.update] / [DecoderSession.finish]
 *   calls). Defends against zip-bomb DoS where a small compressed
 *   payload expands to gigabytes. `null` = unlimited (caller's
 *   responsibility); HTTP client decoders should always set this from
 *   their resource budget
 * @property maxRatio hard cap on output-to-input ratio. Complements
 *   [maxOutputSize] for streams where total input size is unknown
 *   upfront; e.g. `1000` rejects any input that has expanded by more
 *   than 1000× during decode. `null` = unchecked
 */
public class DecoderOptions(
    public val wrapFormat: WrapFormat = WrapFormat.Default,
    public val windowBits: Int? = null,
    public val dictionary: ByteArray? = null,
    public val contextTakeover: Boolean = true,
    public val maxOutputSize: Long? = null,
    public val maxRatio: Int? = null,
) {
    public companion object {
        public val Default: DecoderOptions = DecoderOptions()
    }
}

/**
 * Output framing for compressed bytes.
 *
 * - [Default]: backend-defined default (zlib backend defaults to [Gzip]
 *   for the `gzip` codec and [Zlib] for the `deflate` codec)
 * - [Gzip]: RFC 1952 — 10-byte header + DEFLATE stream + CRC32 + ISIZE
 * - [Zlib]: RFC 1950 — 2-byte zlib wrapper + DEFLATE stream + Adler-32
 * - [Raw]: no wrapper, raw DEFLATE bits. Used by WebSocket
 *   `permessage-deflate` (negotiated via `client_max_window_bits` /
 *   `server_max_window_bits`)
 *
 * Brotli / zstd backends ignore this field — their wire formats are
 * fixed and don't have alternate framings.
 */
public enum class WrapFormat { Default, Gzip, Zlib, Raw }

/**
 * Flush behaviour at message / chunk boundaries.
 *
 * - [NoFlush]: only flush on [EncoderSession.finish]. Best
 *   compression but worst latency / partial-read responsiveness
 * - [Sync]: byte-align output at boundary, allowing receiver to
 *   decompress everything seen so far. Inserts a 4-byte sync marker
 *   (`00 00 ff ff`) for `deflate`. **Default**, matches HTTP
 *   chunked streaming and WebSocket `permessage-deflate`
 * - [Full]: like [Sync] but also resets the encoder window state at
 *   the boundary, allowing the decoder to recover from a missing
 *   byte range without losing the rest. Higher overhead than [Sync]
 * - [Block]: zlib `Z_BLOCK` — finish current block but don't byte-align
 *   or flush dictionaries. Mostly useful for streaming inspection
 *   tooling; rarely needed by application code
 */
public enum class FlushMode { NoFlush, Sync, Full, Block }

/**
 * zlib compression strategy hint. See `deflateInit2` `strategy` argument.
 *
 * Most callers leave this at [Default]. The other values are tuning
 * knobs for specific data shapes (image filters, fixed-width records,
 * pre-compressed inputs).
 */
public enum class Strategy { Default, Filtered, HuffmanOnly, RunLength, Fixed }
