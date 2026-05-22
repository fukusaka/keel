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
 * - **HTTP/1.1 extension (Title-Case)** (~80 entries): provisional
 *   H1 hot-path preset before an empirical wire-frequency study runs.
 *   Three categories: (a) HPACK + QPACK concrete-value non-pseudo
 *   entries Title-Cased for H1 wire convention (filtered to drop
 *   H2/H3 pseudo-headers and name-only sentinels), (b) H1-specific
 *   hop-by-hop / framing / cache-busting pairs absent from QPACK by
 *   design (RFC 9110 §7.6.1, RFC 9112 §6, RFC 9111 §5.2), and
 *   (c) production-frequent variants observed on the H1 wire (browser
 *   default `Accept`, Content-Type charset spacing / case variants,
 *   `X-Frame-Options: DENY` uppercase, etc.) — pragmatic preset
 *   pending BigQuery confirmation in a follow-up PR. Title-Case
 *   matches the H1 wire convention preserved by Netty
 *   `DefaultHttpHeaders` and OkHttp `Headers`, so the [tryInternAt]
 *   exact-case compare hits H1 application input.
 *
 * **Why H1 hot path needs separate Title-Case entries**:
 * [tryInternAt] uses exact-case name compare to preserve the
 * application's chosen case on H1 wire (RFC 9110 §5.1 allows any case
 * but case-as-authored is the H1 convention preserved by Netty / OkHttp).
 * An H1 caller adding `Accept-Encoding: gzip, deflate, br` (Title-Case)
 * does **not** hit QPACK index 31 `accept-encoding: gzip, deflate, br`
 * (lowercase) — that lowercase entry serves only the H2/H3 decoder
 * indexed-entry path. The H1 extension Title-Cases the HPACK + QPACK
 * concrete-value pairs (filtered to drop pseudo-headers and name-only
 * sentinels), the H1-specific hop-by-hop / framing / cache-busting set,
 * and the production-frequent preset to cover the H1 hot path. A
 * BigQuery follow-up PR will refine the production-frequent set with
 * empirical wire-frequency data.
 *
 * **Layout**: a hash-bucket structure with [BUCKET_COUNT] buckets
 * and a low-bit mask (§46.12 mixing audit picked mask as the
 * empirical optimum for the `31 * h + c` polynomial family). Two
 * choices that differ from [HttpHeaders]:
 *
 * 1. **Hashed by `(name, value)` combined** (not name-only) via
 *    [combinedHash] = `nameHashLower * 31 + value.hashCode()`. Popular
 *    names like `Content-Type` carry ~18 value variants here; the
 *    name-only hash piled them all into a single bucket (max depth 31
 *    in an earlier revision), the combined hash spreads each variant
 *    into a different bucket.
 * 2. **`BUCKET_COUNT = 256`** (vs HttpHeaders' 64). The
 *    `StaticHeaderTableBucketCountAuditTest` measured chain depth at
 *    32 / 64 / 128 / 256 / 512 on this table's 242 entries and picked
 *    256 as the smallest where max chain walk stops dropping (max =
 *    6 at both 256 and 512). 1 KB `bucketHead` is trivial for a
 *    process-wide singleton.
 *
 * Result: avg chain depth 0.95, max 6, p99 6. `String.hashCode()` is
 * cached on the JVM so the combine cost is one multiply on the
 * [HttpHeaders.add] hot path. The `e.hashLower == nameHashLower` int
 * compare in [tryInternAt] short-circuits non-matching entries early;
 * byte-equality runs only on the rare full chain match.
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
        // HTTP/1.1 extension (Title-Case): provisional H1 hot-path preset.
        // ===================================================================
        //
        // Title-Case names match the HTTP/1.1 wire convention preserved by
        // Netty `DefaultHttpHeaders` / OkHttp `Headers`, so the exact-case
        // [tryInternAt] compare hits the typical H1 application input
        // (`headers.add("Cache-Control", "no-cache")`). The lowercase
        // HPACK / QPACK entries above intentionally do **not** hit H1
        // Title-Case input — they serve only the H2 / H3 indexed-entry
        // decode path.
        //
        // **Intent**: build a high-hit-rate preset for the H1 hot path
        // **before** an empirical wire-frequency study runs (see
        // "BigQuery confirmation" note at the end). Three inclusion
        // categories:
        //
        // (a) **HPACK + QPACK concrete-value non-pseudo entries, Title-Cased**.
        //     HPACK / QPACK names are RFC-mandated lowercase (RFC 9113
        //     §8.2.1 / RFC 9114 §4.2). The same `(name, value)` pairs on
        //     H1 wire conventionally use Title-Case. Title-Case copies
        //     are added so `Accept-Encoding: gzip, deflate, br`
        //     (Title-Case application input) hits the same `(name, value)`
        //     pair that QPACK index 31 covers in lowercase for H3.
        //     Filtering: skip H2/H3 pseudo-headers (`:authority`,
        //     `:method`, `:path`, `:scheme`, `:status`; absent from H1
        //     wire) and skip name-only sentinels (empty value never
        //     matches non-empty H1 application input). Name conversion
        //     is the standard Title-Case rule: each `-` separated token
        //     has its first character upper, remaining lower.
        // (b) **HTTP/1.1-specific pairs that QPACK omitted by design**:
        //     hop-by-hop headers (RFC 9110 §7.6.1: `Connection`, `Upgrade`),
        //     chunked / identity framing (RFC 9112 §6 `Transfer-Encoding`),
        //     HTTP/1.0 cache-busting carry-over (RFC 9111 §5.2 `Pragma`,
        //     §5.3 `Expires`). H2 / H3 forbid hop-by-hop headers and use
        //     frame-level framing, so QPACK excluded these.
        // (c) **Production-frequent variants observed on the H1 wire**.
        //     Pragmatic preset: pairs that real-world H1 traffic exhibits
        //     in significant volume but that don't appear in HPACK /
        //     QPACK with the wire form authors use. Selected by
        //     inspection of common frameworks and browsers (Chrome /
        //     Firefox H1 request defaults, Spring Boot / Express / Rails
        //     response defaults, Mozilla security header docs). Examples:
        //     browser default `Accept`, Content-Type charset spacing /
        //     case variants, `X-Frame-Options: DENY` / `SAMEORIGIN`
        //     uppercase (real wire is uppercase even though QPACK
        //     normalized to lowercase), bare `Cache-Control: private` /
        //     `public`, `Content-Encoding: deflate`. Without empirical
        //     evidence these would be unprincipled, but as a provisional
        //     preset they significantly raise H1 hit rate before the
        //     BigQuery study lands.
        //
        // **BigQuery confirmation (follow-up PR)**: the QPACK static
        // table (RFC 9204 Appendix A) was generated by the IETF QUIC WG
        // from HTTP Archive BigQuery 2018 crawl with the procedure
        // documented at https://github.com/quicwg/base-drafts/wiki/QPACK-Static-Table:
        // drop vendor-proprietary headers, include values that account
        // for >5 % of occurrences of their name, order by frequency.
        // A follow-up PR will replay that procedure against a recent
        // HTTP Archive crawl restricted to HTTP/1.1, and add / remove /
        // reorder the category (c) entries based on the resulting
        // frequency table. SQL queries + result CSV will be committed
        // alongside the entry changes for reproducibility. BigQuery scan
        // is ~600-800 GB / ~5 queries, within the 1 TB monthly free tier
        // given `WHERE date = ... AND client = ...` partition filters;
        // zero monetary cost under sandbox billing.
        // ===================================================================

        fun h1(name: String, value: String) = entries.add(make(name, value))

        // --- (a-1) HPACK concrete-value non-pseudo, Title-Cased ---
        // Only HPACK index 16 has both a concrete value and a non-pseudo
        // name; indices 2-14 are `:method` / `:path` / `:scheme` /
        // `:status` pseudo-headers and the rest are name-only sentinels.
        h1("Accept-Encoding", "gzip, deflate") // HPACK 16

        // --- (a-2) QPACK concrete-value non-pseudo, Title-Cased ---
        h1("Age", "0") // QPACK 2
        h1("Content-Length", "0") // QPACK 4
        h1("Accept", "*/*") // QPACK 29
        h1("Accept", "application/dns-message") // QPACK 30
        h1("Accept-Encoding", "gzip, deflate, br") // QPACK 31
        h1("Accept-Ranges", "bytes") // QPACK 32
        h1("Access-Control-Allow-Headers", "cache-control") // QPACK 33
        h1("Access-Control-Allow-Headers", "content-type") // QPACK 34
        h1("Access-Control-Allow-Origin", "*") // QPACK 35
        h1("Cache-Control", "max-age=0") // QPACK 36
        h1("Cache-Control", "max-age=2592000") // QPACK 37
        h1("Cache-Control", "max-age=604800") // QPACK 38
        h1("Cache-Control", "no-cache") // QPACK 39
        h1("Cache-Control", "no-store") // QPACK 40
        h1("Cache-Control", "public, max-age=31536000") // QPACK 41
        h1("Content-Encoding", "br") // QPACK 42
        h1("Content-Encoding", "gzip") // QPACK 43
        h1("Content-Type", "application/dns-message") // QPACK 44
        h1("Content-Type", "application/javascript") // QPACK 45
        h1("Content-Type", "application/json") // QPACK 46
        h1("Content-Type", "application/x-www-form-urlencoded") // QPACK 47
        h1("Content-Type", "image/gif") // QPACK 48
        h1("Content-Type", "image/jpeg") // QPACK 49
        h1("Content-Type", "image/png") // QPACK 50
        h1("Content-Type", "text/css") // QPACK 51
        h1("Content-Type", "text/html; charset=utf-8") // QPACK 52
        h1("Content-Type", "text/plain") // QPACK 53
        h1("Content-Type", "text/plain;charset=utf-8") // QPACK 54
        h1("Range", "bytes=0-") // QPACK 55
        h1("Strict-Transport-Security", "max-age=31536000") // QPACK 56
        h1("Strict-Transport-Security", "max-age=31536000; includesubdomains") // QPACK 57
        h1("Strict-Transport-Security", "max-age=31536000; includesubdomains; preload") // QPACK 58
        h1("Vary", "accept-encoding") // QPACK 59
        h1("Vary", "origin") // QPACK 60
        h1("X-Content-Type-Options", "nosniff") // QPACK 61
        h1("X-Xss-Protection", "1; mode=block") // QPACK 62 (canonical Title-Case)
        h1("Access-Control-Allow-Credentials", "FALSE") // QPACK 73
        h1("Access-Control-Allow-Credentials", "TRUE") // QPACK 74
        h1("Access-Control-Allow-Headers", "*") // QPACK 75
        h1("Access-Control-Allow-Methods", "get") // QPACK 76
        h1("Access-Control-Allow-Methods", "get, post, options") // QPACK 77
        h1("Access-Control-Allow-Methods", "options") // QPACK 78
        h1("Access-Control-Expose-Headers", "content-length") // QPACK 79
        h1("Access-Control-Request-Headers", "content-type") // QPACK 80
        h1("Access-Control-Request-Method", "get") // QPACK 81
        h1("Access-Control-Request-Method", "post") // QPACK 82
        h1("Alt-Svc", "clear") // QPACK 83
        h1("Content-Security-Policy", "script-src 'none'; object-src 'none'; base-uri 'none'") // QPACK 85
        h1("Early-Data", "1") // QPACK 86
        h1("Purpose", "prefetch") // QPACK 91
        h1("Timing-Allow-Origin", "*") // QPACK 93
        h1("Upgrade-Insecure-Requests", "1") // QPACK 94
        h1("X-Frame-Options", "deny") // QPACK 97
        h1("X-Frame-Options", "sameorigin") // QPACK 98

        // --- (b) HTTP/1.1-specific (RFC 9110 §7.6.1 / 9112 §6 / 9111 §5.2) ---
        // Connection management (RFC 9110 §7.6.1 hop-by-hop)
        h1("Connection", "close")
        h1("Connection", "keep-alive")
        h1("Connection", "Upgrade")
        // Transfer-Encoding framing (RFC 9112 §6.1)
        h1("Transfer-Encoding", "chunked")
        h1("Transfer-Encoding", "identity")
        // Protocol upgrade (RFC 9110 §7.8, RFC 7540 §3.2)
        h1("Upgrade", "websocket")
        h1("Upgrade", "h2c")
        // HTTP/1.0 cache-busting (RFC 9111 §5.2 Pragma, §5.3 Expires)
        h1("Pragma", "no-cache")
        h1("Expires", "0")
        h1("Expires", "Fri, 01 Jan 1990 00:00:00 GMT")
        // Note: `Content-Length: 0` is already covered by QPACK 4
        // Title-Case above; no duplicate entry here.

        // --- (c) Production-frequent H1 preset (pending BigQuery confirmation) ---
        // Selected by inspection of common framework / browser defaults.
        // Will be reviewed / refined by the BigQuery follow-up PR.

        // Browser default Accept (Chrome / Firefox H1 navigation request)
        h1("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        h1("Accept", "application/json") // REST client default

        // Accept-Encoding short forms (HPACK 16 / QPACK 31 cover the longer ones)
        h1("Accept-Encoding", "gzip")

        // Cache-Control bare (QPACK has `public, max-age=31536000` only)
        h1("Cache-Control", "private")
        h1("Cache-Control", "public")

        // Content-Encoding deflate (QPACK has only gzip / br)
        h1("Content-Encoding", "deflate")

        // Content-Type charset spacing + UTF-8 uppercase variants
        // (QPACK 52/54 cover `text/html; charset=utf-8` lowercase and
        //  `text/plain;charset=utf-8` without space; production sends
        //  many other shapes)
        h1("Content-Type", "text/html")
        h1("Content-Type", "text/html; charset=UTF-8")
        h1("Content-Type", "text/plain; charset=utf-8") // with space
        h1("Content-Type", "text/plain; charset=UTF-8")
        h1("Content-Type", "application/json; charset=utf-8")
        h1("Content-Type", "application/json; charset=UTF-8")
        h1("Content-Type", "application/octet-stream")

        // Vary: Accept-Encoding (Title-Case value — Apache / nginx /
        // CloudFront emit Title-Case on the wire; QPACK 59 uses lowercase)
        h1("Vary", "Accept-Encoding")

        // X-Frame-Options uppercase values (Spring Boot / Express /
        // Rails / MDN docs use uppercase even though QPACK 97/98
        // normalized to lowercase)
        h1("X-Frame-Options", "DENY")
        h1("X-Frame-Options", "SAMEORIGIN")

        // X-XSS-Protection — real wire uses `XSS` uppercase even though
        // the canonical Title-Case form is `X-Xss-Protection` (already
        // covered by QPACK 62 Title-Case)
        h1("X-XSS-Protection", "1; mode=block")

        // === End of H1 extension; BigQuery follow-up PR refines below ===

        byIndex = entries.toTypedArray()
        bucketNext = IntArray(byIndex.size)

        // Build the hash bucket chain. Hash is *(name, value)* combined
        // (not name-only like HttpHeaders) — popular names like
        // Content-Type have many value variants in the table and a
        // name-only hash piles them all into one bucket (measured
        // max depth 31 in an earlier revision). Combining the value
        // hash spreads each variant into its own bucket: the
        // verification test now sees max depth ~5-7 instead of 31.
        // Mask formula matches the §46.12 audit (low-bit mask is the
        // empirical optimum for the 31*h+c polynomial family).
        for (i in byIndex.indices) {
            val e = byIndex[i]
            val bucket = bucketOf(combinedHash(e.hashLower, e.value))
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
     * Combines a name's case-insensitive hash with a value's hash for
     * the static intern's bucket placement. `value.hashCode()` is
     * case-sensitive, which matches the byte-exact value semantics of
     * RFC 9110 §5.5; the JVM caches `String.hashCode()` after first
     * call, so on the [HttpHeaders.add] hot path this is effectively
     * free for repeat-used application values.
     */
    private fun combinedHash(nameHashLower: Int, value: CharSequence): Int =
        nameHashLower * 31 + value.hashCode()

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
     * H1 extension (Title-Case) carries H1 hot-path hits.
     *
     * Value comparison is byte-exact (HTTP values are case-sensitive in
     * general per RFC 9110 §5.5).
     */
    internal fun tryInternAt(hash: Int, name: String, value: String): HeaderEntry? {
        val bucket = bucketOf(combinedHash(hash, value))
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

    /**
     * Returns the chain length of each of the [BUCKET_COUNT] hash buckets.
     * Used by `StaticHeaderTableBucketDepthTest` to verify that the
     * polynomial hash + low-bit mask spread the entries evenly enough
     * (no bucket should accumulate a pathologically long chain).
     */
    internal fun bucketDepths(): IntArray {
        val depths = IntArray(BUCKET_COUNT)
        for (b in 0 until BUCKET_COUNT) {
            var idx = bucketHead[b]
            var depth = 0
            while (idx >= 0) {
                depth++
                idx = bucketNext[idx]
            }
            depths[b] = depth
        }
        return depths
    }

    /**
     * Re-hashes all [byIndex] entries into a hypothetical bucket array
     * of size [hypotheticalBucketCount] (must be a power of 2) using
     * the same `combinedHash` formula and low-bit mask, and returns the
     * resulting per-bucket chain depths. Used by the
     * `StaticHeaderTableBucketCountAuditTest` to compare `BUCKET=32 /
     * 64 / 128 / 256 / 512` for this table specifically (rather than
     * relying on the HttpHeaders §46.12 audit which assumed a small
     * per-request name-only-hashed table).
     */
    internal fun hypotheticalBucketDepths(hypotheticalBucketCount: Int): IntArray {
        require(hypotheticalBucketCount > 0 && (hypotheticalBucketCount and (hypotheticalBucketCount - 1)) == 0) {
            "hypotheticalBucketCount must be a power of 2, got $hypotheticalBucketCount"
        }
        val mask = hypotheticalBucketCount - 1
        val depths = IntArray(hypotheticalBucketCount)
        for (i in byIndex.indices) {
            val e = byIndex[i]
            val bucket = combinedHash(e.hashLower, e.value) and mask
            depths[bucket]++
        }
        return depths
    }

    /**
     * Number of hash buckets. Chosen by
     * `StaticHeaderTableBucketCountAuditTest` which measured chain
     * depth at 32 / 64 / 128 / 256 / 512 on the current 242-entry
     * table with the `(name, value)` combined hash:
     *
     *   BUCKET   avg    max    p99    empty   load%   memory
     *   32       7.56   16     16     0       100.0%  128 B
     *   64       3.78   12     12     5        92.2%  256 B
     *   128      1.89    9      9    32        75.0%  512 B
     *   256      0.95    6      6   137        46.5% 1024 B
     *   512      0.47    6      4   380        25.8% 2048 B
     *
     * 256 picked: max chain walk halves vs 64 (12 → 6), `avg < 1`
     * means typical lookup hits an empty or 1-entry bucket, and 256 →
     * 512 stops improving max (returns disappear past 256). Memory
     * cost (1 KB) is trivial for a process-wide singleton. The
     * HttpHeaders BUCKET=64 stays because that table holds only 10-30
     * entries per request — a different inclusion criterion entirely.
     */
    private const val BUCKET_COUNT: Int = 256
    private const val BUCKET_MASK: Int = BUCKET_COUNT - 1
}
