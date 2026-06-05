package io.github.fukusaka.keel.compression

/**
 * Format-specific encoder / decoder tuning, expressed **per compression
 * format**.
 *
 * The format-independent knobs (`level`, `dictionary`, `flushMode`,
 * `contextTakeover`, `wrapFormat`) live on [EncoderOptions] /
 * [DecoderOptions] directly. The knobs that have **no cross-format
 * equivalent** — a DEFLATE `windowBits` (8..15) and Huffman/LZ77
 * `strategy`, a zstd `windowLog` (10..31) and match-finder strategy, a
 * brotli `lgwin` and content `mode` — differ in range, type, and meaning,
 * so they are carried in a sealed hierarchy with one descriptor per format
 * ([DeflateTuning] today; `ZstdTuning` / `BrotliTuning` arrive with those
 * backends). A backend reads the concrete type it understands and ignores
 * the rest:
 *
 * ```
 * val windowBits = (options.tuning as? DeflateTuning)?.windowBits
 * ```
 *
 * Modelling these per-format keeps adding a second backend non-breaking:
 * `EncoderOptions` does not grow a zstd-only field, it just accepts a
 * `ZstdTuning`.
 *
 * @see DeflateTuning
 */
public sealed interface CodecTuning

/**
 * Tuning for a DEFLATE-family backend (`deflate` / `gzip`).
 *
 * Both knobs apply to the encoder; the decoder reads [windowBits] (when it
 * can honor it) and ignores [strategy] (a compressor-only hint), the same
 * way [DeflateCapabilities.supportedStrategies] is a shared descriptor only
 * the encoder consults.
 *
 * @property windowBits explicit LZ77 window-bits override. `-15..-8` = raw
 *   deflate, `8..15` = zlib wrapper, `+16` over zlib = gzip wrapper. Most
 *   callers leave it null (backend default) and select framing via
 *   [EncoderOptions.wrapFormat]; it is provided for protocols that
 *   negotiate a specific window (WebSocket `client_max_window_bits` /
 *   `server_max_window_bits`).
 * @property strategy DEFLATE compression strategy hint. Advisory: a backend
 *   that does not support the requested strategy falls back to
 *   [Strategy.Default] (see [DeflateCapabilities.supportedStrategies]).
 *   Most callers leave it at [Strategy.Default].
 * @throws IllegalArgumentException if [windowBits] is set to a value outside
 *   the zlib-legal forms `-15..-8` (raw deflate), `8..15` (zlib wrapper), or
 *   `24..31` (gzip wrapper = `8..15 + 16`). Rejected at config time so an
 *   out-of-range value fails loudly here instead of reaching `deflateInit2` /
 *   `inflateInit2` (the native `WrapFormat.Default` path forwards the value
 *   unclamped, where zlib would otherwise return an opaque `Z_STREAM_ERROR`)
 *   or being silently clamped on a different backend (JS `coerceIn`).
 */
public class DeflateTuning(
    public val windowBits: Int? = null,
    public val strategy: Strategy = Strategy.Default,
) : CodecTuning {
    init {
        require(windowBits == null || windowBits in -15..-8 || windowBits in 8..15 || windowBits in 24..31) {
            "DeflateTuning.windowBits must be -15..-8 (raw), 8..15 (zlib), or 24..31 (gzip) if set (got $windowBits)"
        }
    }
}
