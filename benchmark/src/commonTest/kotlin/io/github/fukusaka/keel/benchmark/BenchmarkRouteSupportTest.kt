package io.github.fukusaka.keel.benchmark

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the pure helpers in `BenchmarkRouteSupport.kt`.
 *
 * These cover the routing-handler primitives that are otherwise only
 * exercised end-to-end through k6 bench scenarios. Running them under
 * `:benchmark:jvmTest` catches regressions before they corrupt bench
 * numbers — e.g. a chunk-spanning boundary miss would silently lower
 * `X-Parts-Received` and look like a parser-cost win on the bench
 * leaderboard.
 */
class BenchmarkRouteSupportTest {

    @Test
    fun `parseBenchmarkQueryInt returns null for null query`() {
        assertNull(parseBenchmarkQueryInt(null, "count"))
    }

    @Test
    fun `parseBenchmarkQueryInt returns null for empty query`() {
        assertNull(parseBenchmarkQueryInt("", "count"))
    }

    @Test
    fun `parseBenchmarkQueryInt parses bare key=value`() {
        assertEquals(10, parseBenchmarkQueryInt("count=10", "count"))
    }

    @Test
    fun `parseBenchmarkQueryInt parses leading question mark form`() {
        assertEquals(10, parseBenchmarkQueryInt("?count=10", "count"))
    }

    @Test
    fun `parseBenchmarkQueryInt parses key in middle of multi-pair query`() {
        assertEquals(1024, parseBenchmarkQueryInt("count=10&size=1024&extra=x", "size"))
    }

    @Test
    fun `parseBenchmarkQueryInt parses key at tail`() {
        assertEquals(7, parseBenchmarkQueryInt("count=10&size=1024&n=7", "n"))
    }

    @Test
    fun `parseBenchmarkQueryInt returns null when key is missing`() {
        assertNull(parseBenchmarkQueryInt("count=10&size=1024", "missing"))
    }

    @Test
    fun `parseBenchmarkQueryInt returns null when value is not an int`() {
        assertNull(parseBenchmarkQueryInt("count=abc", "count"))
    }

    @Test
    fun `parseBenchmarkQueryInt skips empty key pairs`() {
        // Defensive: '=value' or '&&' style segments shouldn't crash.
        assertEquals(5, parseBenchmarkQueryInt("=junk&count=5", "count"))
        assertEquals(5, parseBenchmarkQueryInt("&count=5", "count"))
    }

    @Test
    fun `parseBenchmarkQueryInt does not match prefix-only key`() {
        // Key must match exactly — "siz" should not pick up "size=1024".
        assertNull(parseBenchmarkQueryInt("size=1024", "siz"))
    }

    @Test
    fun `scanMultipartBoundaries empty carry and chunk yields zero count`() {
        val result = scanMultipartBoundaries(
            carry = ByteArray(0),
            chunk = ByteArray(0),
            boundary = BENCHMARK_MULTIPART_BOUNDARY,
        )
        assertEquals(0, result.count)
        assertEquals(0, result.carry.size)
    }

    @Test
    fun `scanMultipartBoundaries single chunk with three parts`() {
        // k6 multipart body: 3 parts → 4 boundary occurrences (one before
        // each part + the trailing closing boundary). The handler
        // subtracts 1 in emitMultipartAck so reported parts = 3.
        val boundary = BENCHMARK_MULTIPART_BOUNDARY
        val body = buildBody(parts = 3, partBytes = 16)
        val result = scanMultipartBoundaries(ByteArray(0), body, boundary)
        assertEquals(4, result.count)
    }

    @Test
    fun `scanMultipartBoundaries split across chunk boundary mid-marker`() {
        val boundary = BENCHMARK_MULTIPART_BOUNDARY
        val body = buildBody(parts = 2, partBytes = 8)
        // Pick a split that bisects the second boundary occurrence so the
        // marker straddles the chunk seam — this is the case the carry
        // buffer exists for.
        val secondBoundaryStart = body.indexOf(boundary, fromIndex = 1)
        require(secondBoundaryStart > 0) { "test fixture must contain ≥2 boundaries" }
        val splitAt = secondBoundaryStart + boundary.size / 2
        val first = body.copyOfRange(0, splitAt)
        val second = body.copyOfRange(splitAt, body.size)

        val r1 = scanMultipartBoundaries(ByteArray(0), first, boundary)
        val r2 = scanMultipartBoundaries(r1.carry, second, boundary)

        // 2 parts + trailing closing = 3 boundaries total, regardless of
        // how the body was split.
        assertEquals(3, r1.count + r2.count)
    }

    @Test
    fun `scanMultipartBoundaries split between every byte still detects all boundaries`() {
        // Stress: feed the body one byte at a time. Carry must reassemble
        // every cross-byte boundary.
        val boundary = BENCHMARK_MULTIPART_BOUNDARY
        val body = buildBody(parts = 4, partBytes = 8)
        var carry = ByteArray(0)
        var total = 0
        for (i in body.indices) {
            val r = scanMultipartBoundaries(carry, byteArrayOf(body[i]), boundary)
            total += r.count
            carry = r.carry
        }
        assertEquals(5, total) // 4 parts + 1 closing
    }

    @Test
    fun `scanMultipartBoundaries carry length is bounded by boundary size minus one`() {
        val boundary = BENCHMARK_MULTIPART_BOUNDARY
        val chunk = ByteArray(1024) { (it and 0xFF).toByte() }
        val r = scanMultipartBoundaries(ByteArray(0), chunk, boundary)
        // Carry must never exceed boundary.size - 1, otherwise the next
        // chunk's combined buffer grows unboundedly.
        assertEquals(boundary.size - 1, r.carry.size)
    }

    @Test
    fun `scanMultipartBoundaries carry shorter than boundary minus one for tiny inputs`() {
        val boundary = BENCHMARK_MULTIPART_BOUNDARY
        val chunk = byteArrayOf(0x01, 0x02)
        val r = scanMultipartBoundaries(ByteArray(0), chunk, boundary)
        // combined size < boundary.size - 1, so carry == combined.
        assertEquals(2, r.carry.size)
        assertContentEquals(chunk, r.carry)
        assertEquals(0, r.count)
    }

    @Test
    fun `scanMultipartBoundaries no false positives on boundary prefix repeated`() {
        // "--KeelBenc" (a prefix-only fragment) must not count as a hit.
        val boundary = BENCHMARK_MULTIPART_BOUNDARY
        val partial = "--KeelBenc--KeelBenc--KeelBenc".encodeToByteArray()
        val r = scanMultipartBoundaries(ByteArray(0), partial, boundary)
        assertEquals(0, r.count)
    }

    @Test
    fun `scanMultipartBoundaries non-overlapping match advances by boundary size`() {
        // Two adjacent boundaries `--K--K` (with K = full boundary) must
        // count as 2 — the second match must start after the first ends.
        val boundary = BENCHMARK_MULTIPART_BOUNDARY
        val combined = ByteArray(boundary.size * 2)
        boundary.copyInto(combined, 0)
        boundary.copyInto(combined, boundary.size)
        val r = scanMultipartBoundaries(ByteArray(0), combined, boundary)
        assertEquals(2, r.count)
    }

    /**
     * Builds a synthetic k6-style multipart body with [parts] file parts of
     * [partBytes] random bytes each. Boundary marker is the bench scenario's
     * [BENCHMARK_MULTIPART_BOUNDARY]. The body shape mirrors what
     * `benchmark/k6/multipart.js` produces, just enough to stress the
     * boundary scanner — content-disposition / content-type headers are
     * stubbed to a plausible byte length.
     */
    private fun buildBody(parts: Int, partBytes: Int): ByteArray {
        val boundary = BENCHMARK_MULTIPART_BOUNDARY
        val crlf = "\r\n".encodeToByteArray()
        val partHeaders = "\r\nContent-Disposition: form-data; name=\"f\"; filename=\"f\"\r\nContent-Type: application/octet-stream\r\n\r\n".encodeToByteArray()
        val partBody = ByteArray(partBytes) { 'x'.code.toByte() }
        val closingSuffix = "--\r\n".encodeToByteArray()

        // Compute total size: parts × (boundary + headers + body + crlf) + closing-boundary + closingSuffix
        val perPart = boundary.size + partHeaders.size + partBody.size + crlf.size
        val total = parts * perPart + boundary.size + closingSuffix.size
        val out = ByteArray(total)

        var pos = 0
        for (i in 0 until parts) {
            boundary.copyInto(out, pos); pos += boundary.size
            partHeaders.copyInto(out, pos); pos += partHeaders.size
            partBody.copyInto(out, pos); pos += partBody.size
            crlf.copyInto(out, pos); pos += crlf.size
        }
        boundary.copyInto(out, pos); pos += boundary.size
        closingSuffix.copyInto(out, pos); pos += closingSuffix.size
        check(pos == total) { "fixture build size mismatch: $pos vs $total" }
        return out
    }

    /** Locate [needle] in this byte array starting at [fromIndex]. Returns -1 if not found. */
    private fun ByteArray.indexOf(needle: ByteArray, fromIndex: Int = 0): Int {
        if (needle.isEmpty()) return fromIndex
        outer@ for (i in fromIndex..size - needle.size) {
            for (k in needle.indices) {
                if (this[i + k] != needle[k]) continue@outer
            }
            return i
        }
        return -1
    }
}
