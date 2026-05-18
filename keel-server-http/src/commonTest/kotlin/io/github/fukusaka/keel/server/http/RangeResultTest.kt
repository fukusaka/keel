package io.github.fukusaka.keel.server.http

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [parseByteRange] — the single- and multi-range `Range`
 * header parser. Pure synchronous logic, so no timeout is needed.
 */
class RangeResultTest {

    private val assetSize = 100L

    @Test
    fun `a normal range parses to its inclusive bounds`() {
        assertEquals(RangeResult.Single(0, 3), parseByteRange("bytes=0-3", assetSize))
    }

    @Test
    fun `an open-ended range runs to the last byte`() {
        assertEquals(RangeResult.Single(2, 99), parseByteRange("bytes=2-", assetSize))
    }

    @Test
    fun `a suffix range selects the last bytes`() {
        assertEquals(RangeResult.Single(97, 99), parseByteRange("bytes=-3", assetSize))
    }

    @Test
    fun `an end past the last byte is clamped`() {
        assertEquals(RangeResult.Single(10, 99), parseByteRange("bytes=10-999", assetSize))
    }

    @Test
    fun `a suffix at least as long as the asset selects the whole asset`() {
        assertEquals(RangeResult.Single(0, 99), parseByteRange("bytes=-200", assetSize))
    }

    @Test
    fun `a single-byte range parses correctly`() {
        assertEquals(RangeResult.Single(0, 0), parseByteRange("bytes=0-0", assetSize))
    }

    @Test
    fun `a range for the last byte parses correctly`() {
        assertEquals(RangeResult.Single(99, 99), parseByteRange("bytes=99-99", assetSize))
    }

    @Test
    fun `a start at the asset size is unsatisfiable`() {
        assertEquals(RangeResult.Unsatisfiable, parseByteRange("bytes=100-150", assetSize))
    }

    @Test
    fun `a zero-length suffix is unsatisfiable`() {
        assertEquals(RangeResult.Unsatisfiable, parseByteRange("bytes=-0", assetSize))
    }

    @Test
    fun `any range against a zero-length asset is unsatisfiable`() {
        assertEquals(RangeResult.Unsatisfiable, parseByteRange("bytes=0-3", 0))
        assertEquals(RangeResult.Unsatisfiable, parseByteRange("bytes=0-", 0))
        assertEquals(RangeResult.Unsatisfiable, parseByteRange("bytes=-5", 0))
    }

    @Test
    fun `a start greater than the end is ignored as a full response`() {
        assertEquals(RangeResult.FullResponse, parseByteRange("bytes=5-2", assetSize))
    }

    @Test
    fun `a non-bytes unit falls back to a full response`() {
        assertEquals(RangeResult.FullResponse, parseByteRange("items=0-3", assetSize))
    }

    @Test
    fun `a malformed range falls back to a full response`() {
        assertEquals(RangeResult.FullResponse, parseByteRange("bytes=abc", assetSize))
        assertEquals(RangeResult.FullResponse, parseByteRange("bytes=", assetSize))
        assertEquals(RangeResult.FullResponse, parseByteRange("bytes=1-x", assetSize))
    }

    @Test
    fun `an empty element in a byte-range-set falls back to a full response`() {
        assertEquals(RangeResult.FullResponse, parseByteRange("bytes=0-1,,3-4", assetSize))
        assertEquals(RangeResult.FullResponse, parseByteRange("bytes=0-1,", assetSize))
    }

    @Test
    fun `two disjoint ranges parse to a coalesced multiple`() {
        assertEquals(
            RangeResult.Multiple(listOf(ByteRange(0, 1), ByteRange(3, 4))),
            parseByteRange("bytes=0-1,3-4", assetSize),
        )
    }

    @Test
    fun `overlapping and adjacent ranges coalesce ascending`() {
        // 0-1 and 1-2 overlap, 5-6 is adjacent only to nothing; result is two disjoint ranges.
        assertEquals(
            RangeResult.Multiple(listOf(ByteRange(0, 2), ByteRange(5, 6))),
            parseByteRange("bytes=0-1,1-2,5-6", assetSize),
        )
    }

    @Test
    fun `adjacent ranges merge into a single range`() {
        // 0-1 and 2-3 are adjacent (gap of zero bytes) — merge, then a lone Single.
        assertEquals(RangeResult.Single(0, 3), parseByteRange("bytes=0-1,2-3", assetSize))
    }

    @Test
    fun `out-of-order ranges are sorted ascending`() {
        assertEquals(
            RangeResult.Multiple(listOf(ByteRange(0, 1), ByteRange(10, 11), ByteRange(20, 21))),
            parseByteRange("bytes=20-21,0-1,10-11", assetSize),
        )
    }

    @Test
    fun `all-unsatisfiable ranges yield Unsatisfiable`() {
        assertEquals(RangeResult.Unsatisfiable, parseByteRange("bytes=100-110,200-210,-0", assetSize))
    }

    @Test
    fun `a partly-satisfiable set keeps only the satisfiable ranges`() {
        // 0-3 is satisfiable, 100-110 is past the end and dropped.
        assertEquals(RangeResult.Single(0, 3), parseByteRange("bytes=0-3,100-110", assetSize))
    }

    @Test
    fun `a syntactically invalid spec inside a set ignores the whole header`() {
        assertEquals(RangeResult.FullResponse, parseByteRange("bytes=0-1,5-2", assetSize))
        assertEquals(RangeResult.FullResponse, parseByteRange("bytes=0-1,abc", assetSize))
    }

    @Test
    fun `a range count over the cap falls back to a full response`() {
        // MAX_RANGE_COUNT + 1 distinct ranges — all individually satisfiable.
        val spec = (0 until MAX_RANGE_COUNT + 1).joinToString(",") { "$it-$it" }
        assertEquals(RangeResult.FullResponse, parseByteRange("bytes=$spec", 1000L))
    }

    @Test
    fun `a range count exactly at the cap is parsed`() {
        val spec = (0 until MAX_RANGE_COUNT).joinToString(",") { "${it * 2}-${it * 2}" }
        val result = parseByteRange("bytes=$spec", 1000L)
        assertEquals(MAX_RANGE_COUNT, (result as RangeResult.Multiple).ranges.size)
    }

    @Test
    fun `a satisfiable-byte sum exceeding the asset falls back to a full response`() {
        // Two overlapping ranges each spanning the whole asset: sum 200 > size 100.
        assertEquals(RangeResult.FullResponse, parseByteRange("bytes=0-99,0-99", assetSize))
    }
}
