package io.github.fukusaka.keel.server.http

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [parseByteRange] — the single-range `Range` header
 * parser. Pure synchronous logic, so no timeout is needed.
 */
class RangeResultTest {

    private val assetSize = 100L

    @Test
    fun `a normal range parses to its inclusive bounds`() {
        assertEquals(RangeResult.Satisfiable(0, 3), parseByteRange("bytes=0-3", assetSize))
    }

    @Test
    fun `an open-ended range runs to the last byte`() {
        assertEquals(RangeResult.Satisfiable(2, 99), parseByteRange("bytes=2-", assetSize))
    }

    @Test
    fun `a suffix range selects the last bytes`() {
        assertEquals(RangeResult.Satisfiable(97, 99), parseByteRange("bytes=-3", assetSize))
    }

    @Test
    fun `an end past the last byte is clamped`() {
        assertEquals(RangeResult.Satisfiable(10, 99), parseByteRange("bytes=10-999", assetSize))
    }

    @Test
    fun `a suffix at least as long as the asset selects the whole asset`() {
        assertEquals(RangeResult.Satisfiable(0, 99), parseByteRange("bytes=-200", assetSize))
    }

    @Test
    fun `a single-byte range parses correctly`() {
        assertEquals(RangeResult.Satisfiable(0, 0), parseByteRange("bytes=0-0", assetSize))
    }

    @Test
    fun `a range for the last byte parses correctly`() {
        assertEquals(RangeResult.Satisfiable(99, 99), parseByteRange("bytes=99-99", assetSize))
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
    fun `a multi-range request falls back to a full response`() {
        assertEquals(RangeResult.FullResponse, parseByteRange("bytes=0-1,3-4", assetSize))
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
}
