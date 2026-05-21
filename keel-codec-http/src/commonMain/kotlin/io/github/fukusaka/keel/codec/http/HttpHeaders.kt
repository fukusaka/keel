package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.io.toDecLongOrNull

/**
 * HTTP header fields (RFC 7230 §3.2).
 *
 * **Storage model** (design.md §46, C2-v5 final, 2026-05-21):
 * - `entries: ArrayList<HeaderEntry>` — insertion-order iteration storage
 * - `bucketHead: IntArray[BUCKET_COUNT=64]` — first entry index per hash bucket (`-1` if empty)
 * - `bucketNext: IntArray` — parallel to `entries`, `bucketNext[i]` = next entry index
 *   in same bucket as `entries[i]` (`-1` if last in chain), grown on demand
 * - `HeaderEntry(hashLower, name, value)` — 24 B on JVM with compressed oops
 *   (the `Int hashLower` slot fits in the alignment padding of a 2-ref class,
 *   so storing the case-folded name hash costs nothing per entry)
 *
 * Achieves near-O(1) lookup (11 ns flat for N=1-50) via the IntArray-indexed
 * bucket chain with C (list-of-entries)'s 24 B HeaderEntry footprint —
 * pareto-optimal across the four-construct comparison in design.md §46.5.
 *
 * The bucket chain is **reverse-insertion-order** (each `add` prepends);
 * `get` walks the full chain and returns the **last match in chain order =
 * first inserted in wire order**, matching Netty `DefaultHeaders.get` and the
 * RFC 7230 §3.2.2 first-value semantic. The per-entry `hashLower` lets the
 * chain walk skip non-matching entries on a single int compare.
 *
 * - Field names are case-insensitive ASCII tokens (RFC 7230 §3.2). Stored
 *   bytes preserve the original case for HTTP/1.1 serialization; the lookup
 *   path folds to lower-case before compare.
 * - Insertion order is preserved; same-name fields keep their relative order
 *   (RFC 7230 §3.2.2).
 * - Set-Cookie must not be comma-joined (RFC 6265) — use [getAll].
 * - OWS (optional whitespace) in field values is stripped by the parser
 *   before storage.
 *
 * Public `String` API is unchanged from the previous `LinkedHashMap × 2`
 * representation. A follow-up (design.md §50, BREAKING) will change the
 * public API to `CharSequence`-first and the `HeaderEntry.value` type to
 * `ByteString` / `IoBufAsciiText` for zero-copy from the recv buffer.
 *
 * **Lifecycle**: instances obtained via [borrow] go back to [HttpHeadersPool]
 * on [release]; the underlying `entries` backing array + `bucketHead` +
 * `bucketNext` are all retained for the next borrower. Direct-constructor
 * instances are GC-managed; `release` is a no-op for them.
 *
 * **HTTP/2 / HTTP/3 forward compatibility**: the `HeaderEntry` type is the
 * same shape every production HTTP/2 codec uses for its HPACK / QPACK
 * static- and dynamic-table entries (Netty `HpackHeaderField`, Jetty
 * `HttpField`, Hyper `Bytes`-backed `Header`, h3 QPACK `HeaderField`).
 * Phase 13 `keel-codec-http2` can hand existing `HeaderEntry` instances
 * directly to the per-request `entries` for zero-allocation indexed reads.
 */
class HttpHeaders private constructor(
    private val entries: ArrayList<HeaderEntry>,
    private val bucketHead: IntArray,
) {

    constructor() : this(ArrayList(INITIAL_ENTRY_CAPACITY), IntArray(BUCKET_COUNT).also { it.fill(-1) })

    // Parallel to `entries`: bucketNext[i] = next entry index in same
    // bucket as entries[i], or -1 if last in chain. Grown on demand.
    private var bucketNext: IntArray = IntArray(INITIAL_ENTRY_CAPACITY)

    private var pooled: Boolean = false

    // --- Access ---

    operator fun get(name: String): String? {
        val hash = caseInsensitiveHash(name)
        val bucket = bucketOf(hash)
        var idx = bucketHead[bucket]
        var value: String? = null
        // Walk the full chain; bucket is prepended on add so chain is
        // reverse-insertion-order. The LAST match in the chain is the
        // FIRST inserted — Netty `DefaultHeaders.get` pattern. Hash
        // pre-check skips the per-byte equality on non-matching entries
        // in the bucket.
        while (idx >= 0) {
            val e = entries[idx]
            if (e.hashLower == hash && e.name.equals(name, ignoreCase = true)) value = e.value
            idx = bucketNext[idx]
        }
        return value
    }

    fun getAll(name: String): List<String> {
        if (entries.isEmpty()) return emptyList()
        var result: MutableList<String>? = null
        // Walk in insertion order via the entries ArrayList.
        for (i in entries.indices) {
            val e = entries[i]
            if (e.name.equals(name, ignoreCase = true)) {
                (result ?: mutableListOf<String>().also { result = it }).add(e.value)
            }
        }
        return result ?: emptyList()
    }

    operator fun contains(name: String): Boolean {
        val hash = caseInsensitiveHash(name)
        val bucket = bucketOf(hash)
        var idx = bucketHead[bucket]
        while (idx >= 0) {
            val e = entries[idx]
            if (e.hashLower == hash && e.name.equals(name, ignoreCase = true)) return true
            idx = bucketNext[idx]
        }
        return false
    }

    val size: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()

    // --- Mutation ---

    fun add(name: String, value: String): HttpHeaders {
        val hash = caseInsensitiveHash(name)
        val bucket = bucketOf(hash)
        val idx = entries.size
        // Static intern: well-known (name, value) pairs share a single
        // process-wide HeaderEntry instance (see [StaticHeaderTable]).
        // Skips the 24-byte HeaderEntry alloc on hit. Per-entry cost is
        // one hash + bucket walk; the chain walk short-circuits on
        // hashLower int compare so the byte-equality cost is bounded by
        // the number of (name, value) variants for the given name
        // (~18 for Content-Type, ~6 for Cache-Control, ≤ 5 elsewhere —
        // see `StaticHeaderTableBucketDepthTest`). Still net positive
        // vs the 24 B alloc on typical CDN / browser workloads where
        // well-known pairs dominate.
        val shared = StaticHeaderTable.tryInternAt(hash, name, value)
        entries.add(shared ?: HeaderEntry(hash, name, value))
        ensureBucketNextCapacity(idx + 1)
        bucketNext[idx] = bucketHead[bucket]
        bucketHead[bucket] = idx
        return this
    }

    operator fun set(name: String, value: String): HttpHeaders {
        removeAll(name)
        add(name, value)
        return this
    }

    fun remove(name: String): HttpHeaders {
        removeAll(name)
        return this
    }

    private fun removeAll(name: String) {
        if (entries.isEmpty()) return
        // Snapshot the surviving entries, then rebuild bucketHead /
        // bucketNext from scratch. Simpler and fault-free vs in-place
        // re-index. Removal is a cold path; the O(N) rebuild is fine.
        var anyRemoved = false
        val kept = ArrayList<HeaderEntry>(entries.size)
        for (i in entries.indices) {
            val e = entries[i]
            if (e.name.equals(name, ignoreCase = true)) {
                anyRemoved = true
            } else {
                kept.add(e)
            }
        }
        if (!anyRemoved) return
        entries.clear()
        entries.addAll(kept)
        bucketHead.fill(-1)
        for (i in entries.indices) {
            val e = entries[i]
            val bucket = bucketOf(e.hashLower)
            ensureBucketNextCapacity(i + 1)
            bucketNext[i] = bucketHead[bucket]
            bucketHead[bucket] = i
        }
    }

    private fun ensureBucketNextCapacity(needed: Int) {
        if (bucketNext.size >= needed) return
        var newSize = if (bucketNext.isEmpty()) INITIAL_ENTRY_CAPACITY else bucketNext.size * 2
        while (newSize < needed) newSize *= 2
        bucketNext = bucketNext.copyOf(newSize)
    }

    // --- Iteration ---

    fun forEach(action: (name: String, value: String) -> Unit) {
        for (i in entries.indices) {
            val e = entries[i]
            action(e.name, e.value)
        }
    }

    fun names(): Set<String> {
        if (entries.isEmpty()) return emptySet()
        val result = linkedSetOf<String>()
        for (i in entries.indices) {
            val n = entries[i].name
            var seen = false
            for (existing in result) {
                if (existing.equals(n, ignoreCase = true)) {
                    seen = true
                    break
                }
            }
            if (!seen) result.add(n)
        }
        return result
    }

    fun entries(): List<Pair<String, String>> =
        List(entries.size) { i -> entries[i].name to entries[i].value }

    fun nameAt(index: Int): String = entries[index].name
    fun valueAt(index: Int): String = entries[index].value

    internal fun getByLowercaseKey(key: String): String? = get(key)

    val contentLength: Long? get() = get(HttpHeaderName.CONTENT_LENGTH_KEY)?.trim()?.toDecLongOrNull()
    val contentType: String? get() = get(HttpHeaderName.CONTENT_TYPE_KEY)
    val isChunked: Boolean get() = get(HttpHeaderName.TRANSFER_ENCODING_KEY)?.contains("chunked", ignoreCase = true) == true
    val connection: String? get() = get(HttpHeaderName.CONNECTION_KEY)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpHeaders) return false
        if (entries.size != other.entries.size) return false
        for (i in entries.indices) {
            val a = entries[i]
            val b = other.entries[i]
            if (!a.name.equals(b.name, ignoreCase = true)) return false
            if (a.value != b.value) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var h = 0
        for (i in entries.indices) {
            val e = entries[i]
            h = 31 * h + e.hashLower
            h = 31 * h + (1 shl 16)
            h = 31 * h + e.value.hashCode()
            h = 31 * h + (1 shl 24)
        }
        return h
    }

    override fun toString(): String = buildString {
        append("HttpHeaders(")
        for (i in entries.indices) {
            if (i > 0) append(", ")
            val e = entries[i]
            append(e.name).append(": ").append(e.value)
        }
        append(")")
    }

    fun release() {
        if (!pooled) return
        resetForReuse()
        HttpHeadersPool.giveBack(this)
    }

    internal fun resetForReuse() {
        entries.clear()
        bucketHead.fill(-1)
    }

    internal fun markPooled() {
        pooled = true
    }

    companion object {
        private const val BUCKET_LOG2: Int = 6

        /**
         * Hash bucket count for the `bucketHead` IntArray. Decided by
         * measurement (`HttpHeadersCdnLookupBenchmark`, 2026-05-21).
         *
         * Individual-name lookup latency on the 23 CDN-typical header
         * set (Cookie / Upgrade-Insecure-Requests / CF-Visitor /
         * CDN-Loop cluster, BUCKET=16-32 chain depth 4):
         *
         *   name                       BUCKET=32   BUCKET=64
         *   ----                       ---------   ---------
         *   Cookie                     13 ns       10 ns  (-3)
         *   CF-Visitor                 16 ns       14 ns  (-2)
         *   CDN-Loop                   14 ns       12 ns  (-2)
         *   Upgrade-Insecure-Requests  30 ns       28 ns  (-2)
         *   (non-cluster names)        11-15 ns    11-15 ns (tie)
         *
         * The 5-lookup batch metric had too much measurement variance
         * (28-38 ns at both BUCKET=32 and =64) to choose between them
         * on that signal alone, but the per-name latencies for the
         * clustered subset are reproducibly faster at BUCKET=64.
         * BUCKET=128 showed no further gain.
         *
         * BUCKET=64 is the conservative default: 2-4 ns saved per
         * clustered-name lookup, no slowdown for non-cluster names,
         * +128 bytes per instance (16 KiB total at MAX_POOLED=64 —
         * trivial). Workloads that never look up the clustered names
         * (Cookie / CDN-Loop / Upgrade-Insecure-Requests) would see
         * identical performance at BUCKET=32, but the future-proofing
         * is cheap.
         */
        private const val BUCKET_COUNT: Int = 1 shl BUCKET_LOG2
        private const val BUCKET_MASK: Int = BUCKET_COUNT - 1

        /**
         * Plain low-bit mask. Decided by measurement
         * (`HttpHeadersBucketDistributionDiagnostic`, 2026-05-21).
         *
         * Three mixing strategies were compared on the
         * production-typical CDN header set:
         *
         *   mask            max chain N=23/N=50 = 2 / 3 at BUCKET=64
         *   XOR spreader    max chain N=23/N=50 = 3 / 4 at BUCKET=64
         *                   (Java HashMap-style `h ^ (h >>> 16)`)
         *   Knuth/Fibonacci max chain N=23/N=50 = 3 / 4 at BUCKET=64
         *                   (multiplicative * GOLDEN_RATIO_INT, the
         *                    pattern `keel-io.LongObjectMap` uses)
         *
         * For the `31 * h + asciiLower(c)` polynomial hash applied to
         * HTTP header names, plain mask at BUCKET=64 is reproducibly
         * the best — both extra mixers compound on top of the
         * polynomial hash in ways that re-introduce clustering on
         * different axes. The same conclusion does **not** transfer
         * to `LongObjectMap` (whose input is raw `Long` keys with no
         * polynomial mixing — there Fibonacci is essential).
         */
        internal fun bucketOf(hash: Int): Int = hash and BUCKET_MASK

        /**
         * Initial size of `entries: ArrayList` + `bucketNext: IntArray`.
         * Decided by measurement (`HttpHeadersCdnWorkloadBenchmark`,
         * 2026-05-21):
         *
         *   INITIAL=8:  direct 1480 / pool 552 B per CDN cycle (N=23)
         *   INITIAL=16: direct 1296 / pool 552
         *   INITIAL=32: direct 1168 / pool 552
         *
         * Pool path is invariant (after warmup the capacity grows to
         * N and stays). Direct-constructor savings at INITIAL=32 are
         * for cold-start large-N construction, which is rare. The
         * typical direct caller is `HttpResponse.of` / `build { }` for
         * server-response headers (N=3-5: Content-Type, Content-Length,
         * Date, Server, Connection); INITIAL=32 would waste ~96 B of
         * unused `Object[]` slack per such instance.
         *
         * 8 is the small-N-direct optimum and irrelevant for the
         * pool-warm hot path.
         */
        private const val INITIAL_ENTRY_CAPACITY: Int = 8

        val EMPTY: HttpHeaders = HttpHeaders()

        fun borrow(): HttpHeaders = HttpHeadersPool.borrow()

        fun build(block: HttpHeaders.() -> Unit): HttpHeaders = HttpHeaders().apply(block)

        fun of(vararg pairs: Pair<String, String>): HttpHeaders {
            val headers = HttpHeaders()
            for ((name, value) in pairs) headers.add(name, value)
            return headers
        }

        internal fun caseInsensitiveHash(s: String): Int {
            var h = 0
            for (i in 0 until s.length) {
                val c = s[i].code
                val folded = if (c in 0x41..0x5A) c + 0x20 else c
                h = 31 * h + folded
            }
            return h
        }
    }
}

/**
 * A single HTTP header field — one `(name, value)` pair preserving
 * the original case of the name as it appeared on the wire, plus the
 * case-insensitive hash of the name for O(1) bucket lookup in
 * [HttpHeaders].
 *
 * 24 B on JVM with compressed oops (12 B header + 4 B `hashLower` + 4 B
 * `name` ref + 4 B `value` ref); the `Int hashLower` slot fits in the
 * alignment padding of a 2-ref class so storing the hash is free.
 *
 * `internal` to keep the public surface on `String` until L7-a-ii
 * (design.md §50) BREAKING changes the API to `CharSequence`-first.
 * Phase 13 (`keel-codec-http2`) will reuse this exact type as the
 * HPACK static / dynamic table entry shape — an indexed entry read
 * costs zero allocation because the table simply hands the existing
 * `HeaderEntry` to the per-request [HttpHeaders.entries].
 */
internal class HeaderEntry(
    val hashLower: Int,
    val name: String,
    val value: String,
)
