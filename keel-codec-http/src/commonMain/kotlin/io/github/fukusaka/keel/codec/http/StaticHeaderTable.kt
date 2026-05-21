package io.github.fukusaka.keel.codec.http

/**
 * Static (process-wide, immutable) intern table for well-known HTTP
 * header (name, value) pairs and name-only pseudo-static entries.
 *
 * Two lookup paths share the same set of `HeaderEntry` instances:
 *
 * 1. **HTTP/1.1 parser** (this PR, design.md §51) — by case-insensitive
 *    `(name, value)` content lookup. On hit, the per-request
 *    [HttpHeaders.entries] reuses the shared instance instead of
 *    allocating a fresh [HeaderEntry] (24 B saved per hit).
 * 2. **HTTP/2 HPACK** / **HTTP/3 QPACK static table** (Phase 13/14
 *    forward compat, design.md §46.12 / §52) — by numeric index
 *    (`indexedHeader[i]`). The decoder hands the shared instance
 *    directly to the per-request [HttpHeaders.entries] for zero
 *    allocation on indexed-entry decode.
 *
 * Sharing the table between H1 and H2/H3 means we never double-allocate
 * a `Connection: close` `HeaderEntry`: the H1 parser intern and the
 * HPACK indexed-entry decode resolve to the same instance.
 *
 * **Population sources**:
 *
 * - **HPACK static table** (RFC 7541 Appendix A, 61 entries): the H2
 *   `:authority` / `:method GET` / `content-length` / `accept-encoding
 *   gzip, deflate` etc. baseline. Indices 1-61.
 * - **Jetty `HttpParser.CACHE` extension set** (~40 additional pairs):
 *   browser-default `Accept` strings, `Cache-Control` variants,
 *   `Host: localhost`, `Transfer-Encoding: chunked`, the
 *   `Content-Type × charset` matrix used by static-asset servers.
 *   Indices 62+. H1 only.
 *
 * **Layout**: a hash-bucket structure parallel to
 * [HttpHeaders]'s own — same `BUCKET_COUNT=64` + plain low-bit mask
 * (Knuth / XOR audit at design.md §46.12 confirmed mask is the
 * empirical optimum for `31 * h + asciiLower(c)` polynomial hashes
 * of HTTP header names). Bucket chains are short (≤ 3 for the 100+
 * entry table at BUCKET=64) — a [tryInternAt] call is a single hash
 * compute + bucket index + 1-3 byte-equality compares.
 */
internal object StaticHeaderTable {

    /**
     * Number of HPACK static-table entries (RFC 7541 Appendix A).
     * Indices `1 .. HPACK_STATIC_COUNT` are HPACK-spec — Phase 13
     * `keel-codec-http2` consumes them by index.
     */
    internal const val HPACK_STATIC_COUNT: Int = 61

    /** All entries, indexed (HPACK entries first, then Jetty CACHE extension). */
    private val byIndex: Array<HeaderEntry>

    /**
     * Bucket head index into [byIndex] (or `-1`). Hashed via the same
     * `31*h + asciiLower(c)` + low-bit mask scheme as [HttpHeaders].
     */
    private val bucketHead: IntArray = IntArray(BUCKET_COUNT).also { it.fill(-1) }

    /** Next-entry-index in same bucket chain (parallel to [byIndex]). */
    private val bucketNext: IntArray

    init {
        val entries = ArrayList<HeaderEntry>(128)

        // HPACK static table (RFC 7541 Appendix A) — indices 1..61.
        // The empty-value variants are placeholders the H2 decoder fills
        // with literal values; for H1 they don't intern anything because
        // the parser never sees an empty value with these names.
        fun hpack(name: String, value: String) = entries.add(make(name, value))
        hpack(":authority", "") // 1
        hpack(":method", "GET") // 2
        hpack(":method", "POST") // 3
        hpack(":path", "/") // 4
        hpack(":path", "/index.html") // 5
        hpack(":scheme", "http") // 6
        hpack(":scheme", "https") // 7
        hpack(":status", "200") // 8
        hpack(":status", "204") // 9
        hpack(":status", "206") // 10
        hpack(":status", "304") // 11
        hpack(":status", "400") // 12
        hpack(":status", "404") // 13
        hpack(":status", "500") // 14
        hpack("accept-charset", "") // 15
        hpack("accept-encoding", "gzip, deflate") // 16
        hpack("accept-language", "") // 17
        hpack("accept-ranges", "") // 18
        hpack("accept", "") // 19
        hpack("access-control-allow-origin", "") // 20
        hpack("age", "") // 21
        hpack("allow", "") // 22
        hpack("authorization", "") // 23
        hpack("cache-control", "") // 24
        hpack("content-disposition", "") // 25
        hpack("content-encoding", "") // 26
        hpack("content-language", "") // 27
        hpack("content-length", "") // 28
        hpack("content-location", "") // 29
        hpack("content-range", "") // 30
        hpack("content-type", "") // 31
        hpack("cookie", "") // 32
        hpack("date", "") // 33
        hpack("etag", "") // 34
        hpack("expect", "") // 35
        hpack("expires", "") // 36
        hpack("from", "") // 37
        hpack("host", "") // 38
        hpack("if-match", "") // 39
        hpack("if-modified-since", "") // 40
        hpack("if-none-match", "") // 41
        hpack("if-range", "") // 42
        hpack("if-unmodified-since", "") // 43
        hpack("last-modified", "") // 44
        hpack("link", "") // 45
        hpack("location", "") // 46
        hpack("max-forwards", "") // 47
        hpack("proxy-authenticate", "") // 48
        hpack("proxy-authorization", "") // 49
        hpack("range", "") // 50
        hpack("referer", "") // 51
        hpack("refresh", "") // 52
        hpack("retry-after", "") // 53
        hpack("server", "") // 54
        hpack("set-cookie", "") // 55
        hpack("strict-transport-security", "") // 56
        hpack("transfer-encoding", "") // 57
        hpack("user-agent", "") // 58
        hpack("vary", "") // 59
        hpack("via", "") // 60
        hpack("www-authenticate", "") // 61
        check(entries.size == HPACK_STATIC_COUNT) {
            "HPACK static table must have $HPACK_STATIC_COUNT entries, got ${entries.size}"
        }

        // Jetty `HttpParser.CACHE` extension — additional well-known
        // (name, value) pairs that frequently appear on the wire.
        // Indices 62+. H1 only; H2 decoder ignores these.
        fun jetty(name: String, value: String) = entries.add(make(name, value))
        jetty("Connection", "close")
        jetty("Connection", "keep-alive")
        jetty("Connection", "Upgrade")
        jetty("Accept-Encoding", "gzip")
        jetty("Accept-Encoding", "gzip, deflate")
        jetty("Accept-Encoding", "gzip, deflate, br")
        jetty("Accept", "*/*")
        jetty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        jetty("Accept", "application/json")
        jetty("Cache-Control", "no-cache")
        jetty("Cache-Control", "max-age=0")
        jetty("Cache-Control", "private")
        jetty("Cache-Control", "public")
        jetty("Cache-Control", "no-store")
        jetty("Content-Length", "0")
        jetty("Content-Encoding", "gzip")
        jetty("Content-Encoding", "deflate")
        jetty("Content-Encoding", "br")
        jetty("Content-Type", "text/plain")
        jetty("Content-Type", "text/plain; charset=utf-8")
        jetty("Content-Type", "text/plain; charset=UTF-8")
        jetty("Content-Type", "text/html")
        jetty("Content-Type", "text/html; charset=utf-8")
        jetty("Content-Type", "text/html; charset=UTF-8")
        jetty("Content-Type", "application/json")
        jetty("Content-Type", "application/json; charset=utf-8")
        jetty("Content-Type", "application/json; charset=UTF-8")
        jetty("Content-Type", "application/octet-stream")
        jetty("Content-Type", "application/x-www-form-urlencoded")
        jetty("Transfer-Encoding", "chunked")
        jetty("Transfer-Encoding", "identity")
        jetty("Pragma", "no-cache")
        jetty("Upgrade", "websocket")
        jetty("Upgrade", "h2c")
        jetty("Expires", "Fri, 01 Jan 1990 00:00:00 GMT") // common cache-busting value
        jetty("Vary", "Accept-Encoding")
        jetty("X-Content-Type-Options", "nosniff")
        jetty("X-Frame-Options", "DENY")
        jetty("X-Frame-Options", "SAMEORIGIN")
        jetty("X-XSS-Protection", "1; mode=block")

        byIndex = entries.toTypedArray()
        bucketNext = IntArray(byIndex.size)

        // Build the hash bucket chain. Same `bucketOf(hash)` formula
        // as HttpHeaders (low-bit mask of polynomial 31*h+c hash —
        // mask is the empirical optimum for this hash family per
        // design.md §46.12 mixing-strategy audit).
        for (i in byIndex.indices) {
            val bucket = bucketOf(byIndex[i].hashLower)
            bucketNext[i] = bucketHead[bucket]
            bucketHead[bucket] = i
        }
    }

    /** Constructs a [HeaderEntry] with the pre-computed case-folded hash. */
    private fun make(name: String, value: String): HeaderEntry =
        HeaderEntry(HttpHeaders.caseInsensitiveHash(name), name, value)

    /** Same bucket function as [HttpHeaders.bucketOf]. */
    private fun bucketOf(hash: Int): Int = hash and BUCKET_MASK

    /**
     * Look up a `(name, value)` pair by content. The caller is
     * expected to have already computed [HttpHeaders.caseInsensitiveHash]
     * on [name] (it's part of the [HttpHeaders.add] hot path), so this
     * method takes the hash to avoid recomputing.
     *
     * Returns the shared [HeaderEntry] on hit, or `null` on miss.
     * On hit, the caller writes the returned instance into
     * `HttpHeaders.entries` instead of allocating a new `HeaderEntry`,
     * saving 24 B per request per hit.
     *
     * Comparison is case-insensitive on name (RFC 7230 §3.2) and
     * byte-exact on value (HTTP values are case-sensitive in general,
     * though some specific values like `Connection: close` happen to
     * be conventionally lower-case).
     */
    internal fun tryInternAt(hash: Int, name: String, value: String): HeaderEntry? {
        val bucket = bucketOf(hash)
        var idx = bucketHead[bucket]
        while (idx >= 0) {
            val e = byIndex[idx]
            // Compare name with **exact case**, not case-insensitive, so a caller
            // that adds `content-type` (lowercase) gets a wire-faithful entry
            // rather than the table's `Content-Type`. HTTP/1.1 names are
            // case-insensitive but the serializer is expected to preserve the
            // case the application chose. The hashLower pre-check still filters
            // most non-matches, and the exact `e.name == name` compare bypasses
            // interning only for the unusual case of a non-conventional case
            // spelling (empirical hit rate loss on production traffic is small —
            // Title-Case is the overwhelming convention for well-known names).
            if (e.hashLower == hash && e.name == name && e.value == value) {
                return e
            }
            idx = bucketNext[idx]
        }
        return null
    }

    /**
     * Returns the HPACK static-table entry at 1-based index [n]
     * (RFC 7541 Appendix A). Phase 13 `keel-codec-http2` decoder
     * uses this for indexed-entry decode; the returned instance is
     * shared with the H1 intern path so a request that has its
     * `:authority` header decoded from HPACK index 1 and then
     * relayed by an HTTP/1.1 client retains the same `HeaderEntry`
     * instance throughout.
     *
     * @throws IndexOutOfBoundsException if [n] is not in `1..HPACK_STATIC_COUNT`
     */
    internal fun hpackStaticEntry(n: Int): HeaderEntry {
        require(n in 1..HPACK_STATIC_COUNT) {
            "HPACK static index must be in 1..$HPACK_STATIC_COUNT, got $n"
        }
        return byIndex[n - 1]
    }

    /** Total entry count (HPACK static + Jetty CACHE extension). */
    internal val size: Int get() = byIndex.size

    private const val BUCKET_COUNT: Int = 64
    private const val BUCKET_MASK: Int = BUCKET_COUNT - 1
}
