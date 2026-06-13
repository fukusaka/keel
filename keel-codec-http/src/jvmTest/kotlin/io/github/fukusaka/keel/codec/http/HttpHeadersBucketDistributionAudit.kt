package io.github.fukusaka.keel.codec.http

import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Diagnostic: measure hash distribution for realistic HTTP header
 * names at varying BUCKET_COUNT. Reports for each bucket size the
 * occupied bucket count, max chain length, average chain length,
 * and the worst-case bucket's contents.
 *
 * Used to validate (or invalidate) BUCKET_COUNT = 16 default in
 * the C2-v5 design. The hash function is the standard Java
 * `String.hashCode()`-style `31*h + asciiToLower(c)`.
 */
// @Ignore: one-time measurement (no functional assertion) — a decision
// aid that caught no regression, so it is not run in the gate / CI; kept
// for re-verification. The verified content + conclusion is the class
// KDoc above.
// Re-run: remove @Ignore, then
//   ./gradlew :keel-codec-http:jvmTest --tests "*HttpHeadersBucketDistributionAudit"
@Ignore
class HttpHeadersBucketDistributionAudit {

    /**
     * 23 production-typical header names (Cloudflare-mediated edge
     * traffic), matches HttpHeadersCdnWorkloadBenchmark fixture.
     */
    private val cdnHeaderNames = listOf(
        // Browser-original (10)
        "Host", "User-Agent", "Accept", "Accept-Language", "Accept-Encoding",
        "Connection", "Cookie", "Upgrade-Insecure-Requests",
        "Sec-Fetch-Dest", "Sec-Fetch-Mode",
        // Cloudflare (8)
        "CF-Connecting-IP", "CF-IPCountry", "CF-Ray", "CF-Visitor", "CF-Worker",
        "X-Forwarded-For", "X-Forwarded-Proto", "X-Real-IP",
        // Tracing (3)
        "traceparent", "tracestate", "X-Request-ID",
        // Auth (1) + CDN-Loop (1)
        "Authorization", "CDN-Loop",
    )

    /**
     * Worst-case keep-alive workload from a single browser session:
     * 50 distinct names (CDN + auth + custom + tracing).
     */
    private val heavyHeaderNames = cdnHeaderNames + listOf(
        // Additional CDN headers
        "True-Client-IP", "X-Original-Forwarded-For", "CF-Cache-Status",
        "Fastly-Client-IP", "Fastly-FF", "X-Timer", "X-Cache", "X-Cache-Hits",
        // CloudFront
        "CloudFront-Viewer-Country", "CloudFront-Forwarded-Proto",
        "CloudFront-Is-Mobile-Viewer", "CloudFront-Is-Tablet-Viewer",
        "CloudFront-Is-Desktop-Viewer",
        // Akamai
        "X-Akamai-Edgescape", "Akamai-User-Country",
        // Custom enterprise
        "X-Tenant-ID", "X-Org-ID", "X-Feature-Flags", "X-AB-Variant",
        "X-Client-Version", "X-Device-ID", "X-Session-ID",
        // OpenTelemetry / B3
        "b3", "x-b3-traceid", "x-b3-spanid", "x-b3-sampled",
        "x-correlation-id",
    )

    private fun caseInsensitiveHash(s: String): Int {
        var h = 0
        for (i in 0 until s.length) {
            val c = s[i].code
            val folded = if (c in 0x41..0x5A) c + 0x20 else c
            h = 31 * h + folded
        }
        return h
    }

    private fun report(names: List<String>, bucketCount: Int, mode: String): String {
        val log2 = Integer.numberOfTrailingZeros(bucketCount)
        val shift = 32 - log2
        val mask = bucketCount - 1
        val chainLen = IntArray(bucketCount)
        val bucketContents = Array(bucketCount) { mutableListOf<String>() }
        for (n in names) {
            val h = caseInsensitiveHash(n)
            val b = when (mode) {
                "mask" -> h and mask
                "xor" -> (h xor (h ushr 16)) and mask
                "knuth" -> (h * GOLDEN_RATIO_INT) ushr shift
                "xor+knuth" -> {
                    val s = h xor (h ushr 16)
                    (s * GOLDEN_RATIO_INT) ushr shift
                }
                else -> error("unknown mode: $mode")
            }
            chainLen[b]++
            bucketContents[b].add(n)
        }
        val occupied = chainLen.count { it > 0 }
        val max = chainLen.max()
        val avg = "%.2f".format(names.size.toDouble() / bucketCount)
        val avgOccupied = "%.2f".format(names.size.toDouble() / occupied)
        val worst = bucketContents.maxByOrNull { it.size } ?: emptyList()
        return buildString {
            append(
                "BUCKET=$bucketCount   N=${names.size}   occupied=$occupied/$bucketCount " +
                    "(${(occupied * 100 / bucketCount)}%)   load_factor=$avg   " +
                    "load_per_occupied=$avgOccupied   max_chain=$max\n",
            )
            append("    worst bucket (${worst.size} entries): $worst\n")
            // Histogram
            val histogram = chainLen.groupBy { it }.mapValues { it.value.size }.toSortedMap()
            append("    chain-length histogram: ")
            for ((len, count) in histogram) append("len=$len → $count buckets   ")
            append("\n")
        }
    }

    @Test
    fun `bucket distribution mixing strategies`() {
        println("=== Hash distribution: 4 mixing strategies ===")
        println()
        println("Header set: CDN-mediated typical (N=${cdnHeaderNames.size})")
        for (bc in intArrayOf(16, 32, 64)) {
            for (mode in listOf("mask", "xor", "knuth", "xor+knuth")) {
                print("[${mode.padEnd(9)}] " + report(cdnHeaderNames, bc, mode))
            }
            println()
        }
        println("Header set: heavy enterprise / multi-CDN (N=${heavyHeaderNames.size})")
        for (bc in intArrayOf(32, 64)) {
            for (mode in listOf("mask", "xor", "knuth", "xor+knuth")) {
                print("[${mode.padEnd(9)}] " + report(heavyHeaderNames, bc, mode))
            }
            println()
        }
    }

    companion object {
        private const val GOLDEN_RATIO_INT: Int = -1640531527 // 0x9E_37_79_B9
    }
}
