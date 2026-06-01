package io.github.fukusaka.keel.compression

/**
 * What a compression backend can honor, expressed **per compression
 * format**.
 *
 * The negotiable / tunable parameters of a compression algorithm are
 * format-specific — DEFLATE has an 8..15 `windowBits` and a Huffman/LZ77
 * `strategy`, zstd has a 10..31 `windowLog` and a match-finder strategy,
 * brotli has `lgwin` and a content `mode` — so capabilities are a sealed
 * hierarchy with one descriptor per format ([DeflateCapabilities] today;
 * `ZstdCapabilities` / `BrotliCapabilities` are added with those
 * backends). A consumer matches on the concrete type:
 *
 * ```
 * when (val caps = codec.encoder.capabilities) {
 *     is DeflateCapabilities -> /* permessage-deflate negotiation */
 *     else -> /* not a DEFLATE backend */
 * }
 * ```
 *
 * @see DeflateCapabilities
 */
public sealed interface CompressionCapabilities

/**
 * Capabilities of a DEFLATE-family backend (`deflate` / `gzip`, which
 * share the RFC 1951 DEFLATE core).
 *
 * keel is Kotlin Multiplatform, so one logical codec (`DeflateCodec`) is
 * backed by a different library per target and those bindings differ in
 * what they can honor — this descriptor surfaces the **irreducible**
 * per-target differences (the ones keel cannot close by wiring more
 * options through, given its chosen backend):
 * - native libz (`deflateInit2`) emits `windowBits` down to 8 and carries
 *   the window across messages.
 * - the JVM `java.util.zip.Deflater` is fixed at a 15-bit window (no API
 *   to shrink it) but carries the window across messages.
 * - the Node one-shot API processes each message independently, so it
 *   cannot carry the window across messages (no context takeover), and
 *   until its `windowBits` option is wired it emits the full window.
 *
 * [windowBits] and [supportsContextTakeover] are exactly what RFC 7692
 * permessage-deflate negotiates (`server_max_window_bits` and
 * `*_no_context_takeover`), so the negotiator consults them as a **gate**
 * to avoid advertising a window the backend cannot produce or a context
 * takeover it cannot honor.
 *
 * [supportedStrategies] is different: a DEFLATE [Strategy] only affects the
 * compression ratio / speed, never the decodability of the output (any
 * strategy yields a valid DEFLATE stream the decoder reads identically).
 * So it is **informational**, not a gate — a backend that does not support
 * a requested strategy silently falls back to [Strategy.Default] rather
 * than declining. It is modelled because the JVM `Deflater` lacks
 * `Z_RLE` / `Z_FIXED` entirely (an irreducible gap), unlike `level` and
 * `dictionary`, which are wireable on every backend (a missing one is a
 * keel wiring gap to close, not a capability).
 *
 * @property windowBits the LZ77 window-bits range the backend's
 *   compressor can emit. `15..15` means "fixed full window" (the JVM and,
 *   for now, JS); native libz reports `8..15`. Negotiation honors an
 *   offered `server_max_window_bits=N` only when `N >= windowBits.first`.
 * @property supportsContextTakeover whether the backend can carry the
 *   LZ77 window across messages — for an encoder, compress with takeover;
 *   for a decoder, decode a peer stream that used it. The Node one-shot
 *   backend reports false.
 * @property supportedStrategies the [Strategy] values the backend's
 *   compressor honors. native libz and Node support all five; the JVM
 *   `Deflater` supports only [Strategy.Default] / [Strategy.Filtered] /
 *   [Strategy.HuffmanOnly] (no `Z_RLE` / `Z_FIXED`). A strategy outside
 *   this set is coerced to [Strategy.Default] by the backend — this is
 *   advisory, so callers need not consult it for correctness; it exists
 *   for tuning diagnostics.
 */
public class DeflateCapabilities(
    public val windowBits: IntRange = FULL_WINDOW_ONLY,
    public val supportsContextTakeover: Boolean = true,
    public val supportedStrategies: Set<Strategy> = ALL_STRATEGIES,
) : CompressionCapabilities {
    public companion object {
        /**
         * The full DEFLATE window only (15..15) — the conservative range
         * for a backend that cannot shrink its window (JVM `Deflater`).
         * RFC 7692 §7.1.2 caps `*_max_window_bits` at 15.
         */
        public val FULL_WINDOW_ONLY: IntRange = 15..15

        /**
         * Every [Strategy] — the default for a backend (native libz, Node)
         * whose compressor maps all five to a `Z_*` strategy constant.
         */
        public val ALL_STRATEGIES: Set<Strategy> = Strategy.entries.toSet()
    }
}
