package io.github.fukusaka.keel.codec.http

/**
 * Static (process-wide, immutable) intern table for well-known HTTP
 * header `(name, value)` pairs.
 *
 * Two access paths share the same set of [HeaderEntry] instances:
 *
 * 1. **HTTP/1.1 parser** — content lookup by `(name, value)` via
 *    [tryInternAt]. On hit, the per-request [HttpHeaders.entries]
 *    reuses the shared instance instead of allocating a fresh
 *    [HeaderEntry] (24 B saved per hit).
 * 2. **HTTP/2 HPACK** / **HTTP/3 QPACK** indexed-entry decode —
 *    numeric index lookup via [hpackStaticEntry] / [qpackStaticEntry]
 *    for the keel-codec-http2 (Phase 13) / keel-codec-http3 (Phase 14)
 *    decoders. The decoder hands the shared instance directly to
 *    [HttpHeaders.entries] for zero allocation on indexed decode.
 *
 * **Population sources**:
 *
 * - **HPACK static table** (RFC 7541 Appendix A, 61 entries): the H2
 *   baseline `(name, value)` set, names lowercase per RFC 9113 §8.2.1
 *   ("field names MUST be converted to lowercase prior to encoding").
 *   Indices 1..61 (1-based per RFC 7541).
 * - **QPACK static table** (RFC 9204 Appendix A, 99 entries): the H3
 *   baseline `(name, value)` set, names lowercase per RFC 9114 §4.2.
 *   The QPACK working group built this from a 2018 HTTP Archive
 *   BigQuery analysis with a >5 % per-name value-frequency threshold
 *   (https://github.com/quicwg/base-drafts/wiki/QPACK-Static-Table).
 *   Indices 0..98 (0-based per RFC 9204).
 * - **HTTP/1.1 hop-by-hop and message-framing extension**
 *   (~11 Title-Case entries): hop-by-hop headers (RFC 9110 §7.6.1),
 *   chunked framing (RFC 9112 §6), protocol upgrade handshake
 *   (RFC 9110 §7.8), and HTTP/1.0 cache-busting idioms (RFC 9111 §5.2).
 *   These are absent from the H2/H3 static tables by design (H2/H3
 *   forbid hop-by-hop headers and use frame-level framing). Title-Case
 *   matches the H1 wire convention so the [tryInternAt] exact-case
 *   compare hits H1 application input.
 *
 * **Why H1 hot path doesn't reuse the HPACK/QPACK lowercase entries**:
 * [tryInternAt] uses exact-case name compare to preserve the
 * application's chosen case on H1 wire (RFC 9110 §5.1 allows any case
 * but case-as-authored is the H1 convention preserved by Netty / OkHttp).
 * An H1 caller adding `Accept-Encoding: gzip, deflate, br` (Title-Case)
 * does **not** hit QPACK index 31 `accept-encoding: gzip, deflate, br`
 * (lowercase) — that lowercase entry serves only the H2/H3 decoder
 * indexed-entry path. The H1 Title-Case hits live in the Tier 2 H1
 * extension below, and (deferred) a Tier 3 set derived from production
 * H1 wire frequency.
 *
 * **Layout**: a hash-bucket structure parallel to [HttpHeaders]'s own —
 * same `BUCKET_COUNT=64` + plain low-bit mask (the §46.12 mixing audit
 * confirmed mask is the empirical optimum for `31 * h + asciiLower(c)`
 * polynomial hashes of HTTP header names). Bucket chains stay short
 * (≤ 4 for the ~170-entry table at BUCKET=64) — a [tryInternAt] call is
 * a single hash compute + bucket index + 1-4 byte-equality compares.
 */
internal object StaticHeaderTable {

    /**
     * Number of HPACK static-table entries (RFC 7541 Appendix A).
     * Indices `1..HPACK_STATIC_COUNT` are HPACK-spec — Phase 13
     * `keel-codec-http2` consumes them by index via [hpackStaticEntry].
     */
    internal const val HPACK_STATIC_COUNT: Int = 61

    /**
     * Number of QPACK static-table entries (RFC 9204 Appendix A).
     * QPACK uses 0-based indexing (`0..QPACK_STATIC_COUNT - 1`).
     * Phase 14 `keel-codec-http3` consumes them via [qpackStaticEntry].
     */
    internal const val QPACK_STATIC_COUNT: Int = 99

    /** All entries, indexed: HPACK first, then QPACK, then H1 extension. */
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

        // QPACK static table (RFC 9204 Appendix A) — indices 0..98
        // (0-based). Lowercase names per RFC 9114 §4.2 wire mandate.
        // Phase 14 `keel-codec-http3` decoder consumes by index.
        fun qpack(name: String, value: String) = entries.add(make(name, value))
        qpack(":authority", "") // 0
        qpack(":path", "/") // 1
        qpack("age", "0") // 2
        qpack("content-disposition", "") // 3
        qpack("content-length", "0") // 4
        qpack("cookie", "") // 5
        qpack("date", "") // 6
        qpack("etag", "") // 7
        qpack("if-modified-since", "") // 8
        qpack("if-none-match", "") // 9
        qpack("last-modified", "") // 10
        qpack("link", "") // 11
        qpack("location", "") // 12
        qpack("referer", "") // 13
        qpack("set-cookie", "") // 14
        qpack(":method", "CONNECT") // 15
        qpack(":method", "DELETE") // 16
        qpack(":method", "GET") // 17
        qpack(":method", "HEAD") // 18
        qpack(":method", "OPTIONS") // 19
        qpack(":method", "POST") // 20
        qpack(":method", "PUT") // 21
        qpack(":scheme", "http") // 22
        qpack(":scheme", "https") // 23
        qpack(":status", "103") // 24
        qpack(":status", "200") // 25
        qpack(":status", "304") // 26
        qpack(":status", "404") // 27
        qpack(":status", "503") // 28
        qpack("accept", "*/*") // 29
        qpack("accept", "application/dns-message") // 30
        qpack("accept-encoding", "gzip, deflate, br") // 31
        qpack("accept-ranges", "bytes") // 32
        qpack("access-control-allow-headers", "cache-control") // 33
        qpack("access-control-allow-headers", "content-type") // 34
        qpack("access-control-allow-origin", "*") // 35
        qpack("cache-control", "max-age=0") // 36
        qpack("cache-control", "max-age=2592000") // 37
        qpack("cache-control", "max-age=604800") // 38
        qpack("cache-control", "no-cache") // 39
        qpack("cache-control", "no-store") // 40
        qpack("cache-control", "public, max-age=31536000") // 41
        qpack("content-encoding", "br") // 42
        qpack("content-encoding", "gzip") // 43
        qpack("content-type", "application/dns-message") // 44
        qpack("content-type", "application/javascript") // 45
        qpack("content-type", "application/json") // 46
        qpack("content-type", "application/x-www-form-urlencoded") // 47
        qpack("content-type", "image/gif") // 48
        qpack("content-type", "image/jpeg") // 49
        qpack("content-type", "image/png") // 50
        qpack("content-type", "text/css") // 51
        qpack("content-type", "text/html; charset=utf-8") // 52
        qpack("content-type", "text/plain") // 53
        qpack("content-type", "text/plain;charset=utf-8") // 54
        qpack("range", "bytes=0-") // 55
        qpack("strict-transport-security", "max-age=31536000") // 56
        qpack("strict-transport-security", "max-age=31536000; includesubdomains") // 57
        qpack("strict-transport-security", "max-age=31536000; includesubdomains; preload") // 58
        qpack("vary", "accept-encoding") // 59
        qpack("vary", "origin") // 60
        qpack("x-content-type-options", "nosniff") // 61
        qpack("x-xss-protection", "1; mode=block") // 62
        qpack(":status", "100") // 63
        qpack(":status", "204") // 64
        qpack(":status", "206") // 65
        qpack(":status", "302") // 66
        qpack(":status", "400") // 67
        qpack(":status", "403") // 68
        qpack(":status", "421") // 69
        qpack(":status", "425") // 70
        qpack(":status", "500") // 71
        qpack("accept-language", "") // 72
        qpack("access-control-allow-credentials", "FALSE") // 73
        qpack("access-control-allow-credentials", "TRUE") // 74
        qpack("access-control-allow-headers", "*") // 75
        qpack("access-control-allow-methods", "get") // 76
        qpack("access-control-allow-methods", "get, post, options") // 77
        qpack("access-control-allow-methods", "options") // 78
        qpack("access-control-expose-headers", "content-length") // 79
        qpack("access-control-request-headers", "content-type") // 80
        qpack("access-control-request-method", "get") // 81
        qpack("access-control-request-method", "post") // 82
        qpack("alt-svc", "clear") // 83
        qpack("authorization", "") // 84
        qpack("content-security-policy", "script-src 'none'; object-src 'none'; base-uri 'none'") // 85
        qpack("early-data", "1") // 86
        qpack("expect-ct", "") // 87
        qpack("forwarded", "") // 88
        qpack("if-range", "") // 89
        qpack("origin", "") // 90
        qpack("purpose", "prefetch") // 91
        qpack("server", "") // 92
        qpack("timing-allow-origin", "*") // 93
        qpack("upgrade-insecure-requests", "1") // 94
        qpack("user-agent", "") // 95
        qpack("x-forwarded-for", "") // 96
        qpack("x-frame-options", "deny") // 97
        qpack("x-frame-options", "sameorigin") // 98
        check(entries.size == HPACK_STATIC_COUNT + QPACK_STATIC_COUNT) {
            "QPACK static table must have $QPACK_STATIC_COUNT entries; total at end of QPACK section " +
                "expected ${HPACK_STATIC_COUNT + QPACK_STATIC_COUNT}, got ${entries.size}"
        }

        // ===================================================================
        // HTTP/1.1 extension (Tier 2): hop-by-hop + framing + H1.0 carry-over.
        // ===================================================================
        //
        // Title-Case names match the HTTP/1.1 wire convention preserved by
        // Netty `DefaultHttpHeaders` / OkHttp `Headers`, so the exact-case
        // [tryInternAt] compare hits the typical H1 application input
        // (`headers.add("Connection", "close")`). The lowercase HPACK / QPACK
        // entries above intentionally do **not** hit H1 Title-Case input —
        // they serve only the H2 / H3 indexed-entry decode path.
        //
        // === Inclusion criteria (Tier 2) ===
        //
        // RFC-derived only. Each entry has an explicit reference to the
        // HTTP/1.1 wire feature it represents. QPACK explicitly excludes
        // these by design because H2 / H3 forbid hop-by-hop headers and use
        // frame-level framing.
        //
        // === Tier 3 (production wire frequency, DEFERRED to follow-up PR) ===
        //
        // **Not included here**: browser-default `Accept`, Content-Type
        // `charset` spacing / case variants (`text/plain; charset=utf-8`
        // with space, `; charset=UTF-8` uppercase), Cache-Control bare
        // `private` / `public`, `Content-Encoding: deflate`,
        // `X-Frame-Options: DENY` / `SAMEORIGIN` (uppercase values — QPACK
        // 97/98 normalize to lowercase but real wire is uppercase per
        // Spring Boot / Express / Rails defaults), `Vary: Accept-Encoding`
        // (Title-Case version of QPACK 59).
        //
        // These pairs are observed to be H1-frequent in production but lack
        // an RFC source. Including them on a "library X does it this way"
        // basis (e.g. Jetty `HttpParser.CACHE`) was rejected as unprincipled.
        // The follow-up PR will derive them empirically by replaying the
        // QPACK methodology against a recent HTTP Archive crawl.
        //
        // === Tier 3 derivation methodology ===
        //
        // Source: HTTP Archive BigQuery public dataset (https://httparchive.org,
        // `httparchive.crawl.requests`, monthly crawl of ~16M top-rank URLs
        // since 2010). The QPACK static table (RFC 9204 Appendix A) was
        // itself derived from this dataset's 2018 crawl by the IETF QUIC WG
        // (https://github.com/quicwg/base-drafts/wiki/QPACK-Static-Table)
        // with the following procedure:
        //
        //   1. Filter: drop vendor-proprietary and non-HQ-compatible headers
        //      (`user-agent`, `youtube-client-id`, etc.).
        //   2. Inclusion threshold (value): include if value accounts for
        //      >5 % of occurrences of that header name.
        //   3. Ranking: by total frequency of the header's occurrence
        //      (both requests and responses).
        //   4. Value ordering: by percentage of responses.
        //   5. Additions: a few spec-required entries not present in the
        //      archive (e.g. uncommon `:status` codes).
        //
        // keel's Tier 3 PR will replay the same methodology against a recent
        // (2024-06 or later) HTTP Archive crawl restricted to HTTP/1.1
        // request/response pairs, and additionally include the H1-specific
        // variants that QPACK filtered out by design. The SQL queries and
        // result CSVs will be committed alongside the new entries for full
        // reproducibility.
        //
        // BigQuery cost: ~600-800 GB scan total across the ~5 queries
        // needed, well within the 1 TB monthly free tier when the queries
        // use `WHERE date = ... AND client = ...` partition filters. Zero
        // monetary cost under sandbox billing.
        //
        // **Until the Tier 3 PR lands**, H1 traffic carrying values like:
        //   `Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8`
        //   `Content-Type: text/html; charset=utf-8` (with space)
        //   `X-Frame-Options: DENY` (uppercase)
        // will **miss** the intern table and pay the 24 B per-request
        // [HeaderEntry] allocation, as they did before this PR.
        //
        // ===================================================================

        fun h1(name: String, value: String) = entries.add(make(name, value))

        // --- Connection management (RFC 9110 §7.6.1 hop-by-hop) ---
        h1("Connection", "close")
        h1("Connection", "keep-alive")
        h1("Connection", "Upgrade")

        // --- Message framing (RFC 9112 §6 Transfer-Encoding, §6.2 body length) ---
        h1("Transfer-Encoding", "chunked")
        h1("Transfer-Encoding", "identity")
        h1("Content-Length", "0")

        // --- Protocol upgrade (RFC 9110 §7.8 Upgrade, RFC 7540 §3.2 h2c) ---
        h1("Upgrade", "websocket")
        h1("Upgrade", "h2c")

        // --- HTTP/1.0 cache-busting carry-over (RFC 9111 §5.2 Pragma, §5.3 Expires) ---
        h1("Pragma", "no-cache")
        h1("Expires", "0")
        h1("Expires", "Fri, 01 Jan 1990 00:00:00 GMT")

        // === End of Tier 2 entries; Tier 3 follow-up PR appends below ===

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
     * Name comparison is **exact case** (not case-insensitive), so the
     * H1 wire case authored by the application (`Content-Type` vs
     * `content-type`) survives interning — matching the case-preservation
     * behavior of Netty `DefaultHttpHeaders` and OkHttp `Headers`. The
     * lowercase HPACK / QPACK entries therefore do not hit H1 Title-Case
     * input; they serve only the H2 / H3 indexed-entry decode path. The
     * H1 Tier 2 extension (Title-Case) carries H1 hot-path hits.
     *
     * Value comparison is byte-exact (HTTP values are case-sensitive in
     * general per RFC 9110 §5.5).
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
     * uses this for indexed-entry decode.
     *
     * @throws IllegalArgumentException if [n] is not in `1..HPACK_STATIC_COUNT`
     */
    internal fun hpackStaticEntry(n: Int): HeaderEntry {
        require(n in 1..HPACK_STATIC_COUNT) {
            "HPACK static index must be in 1..$HPACK_STATIC_COUNT, got $n"
        }
        return byIndex[n - 1]
    }

    /**
     * Returns the QPACK static-table entry at 0-based index [n]
     * (RFC 9204 Appendix A). Phase 14 `keel-codec-http3` decoder
     * uses this for indexed-entry decode.
     *
     * QPACK entries occupy positions `HPACK_STATIC_COUNT..
     * HPACK_STATIC_COUNT + QPACK_STATIC_COUNT - 1` in the underlying
     * [byIndex] array, so this method offsets by [HPACK_STATIC_COUNT].
     *
     * @throws IllegalArgumentException if [n] is not in `0..QPACK_STATIC_COUNT - 1`
     */
    internal fun qpackStaticEntry(n: Int): HeaderEntry {
        require(n in 0 until QPACK_STATIC_COUNT) {
            "QPACK static index must be in 0..${QPACK_STATIC_COUNT - 1}, got $n"
        }
        return byIndex[HPACK_STATIC_COUNT + n]
    }

    /** Total entry count (HPACK + QPACK + H1 extension). */
    internal val size: Int get() = byIndex.size

    private const val BUCKET_COUNT: Int = 64
    private const val BUCKET_MASK: Int = BUCKET_COUNT - 1
}
