package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Semantic parity of the zero-materialise [HttpHeaders.contentLength] /
 * [HttpHeaders.isChunked] fast path over **buffer-backed range slots**
 * (the decoder's `addRange` parse path) with the previous
 * `getString(...)?.trim()?.toDecLongOrNull()` shape.
 *
 * The existing [HttpHeadersTest] cases cover the string-entry path
 * (`add` / `of`), which is unchanged. These tests pin the in-place
 * [io.github.fukusaka.keel.io.parseDecLongAt] branch: plain values,
 * whitespace trimming, malformed input, sign handling, overflow, and
 * first-value semantics for duplicated headers — each asserted against
 * the materialising reference (`getString` + trim + parse) so a
 * divergence between the two paths fails loudly.
 *
 * Buffers are allocated at a power-of-two capacity and every helper
 * asserts the creator's `release()` returns `false` after `addRange` —
 * a positive control that the entry really took the range path (the
 * capacity guard silently degrades non-power-of-two buffers to string
 * entries, which would test the wrong branch).
 */
class HttpHeadersContentLengthRangeTest {

    /**
     * Packs `name + value` into a fresh power-of-two-capacity buffer and
     * adds it as a range slot. Ownership of the buffer transfers to the
     * headers (the creator reference is dropped here, under a positive
     * control that `addRange` retained it), so callers only `release()`
     * the returned headers.
     */
    private fun rangeHeaderOf(name: String, value: String): HttpHeaders {
        val bytes = (name + value).encodeToByteArray()
        var cap = 1
        while (cap < bytes.size) cap = cap shl 1
        val buf: IoBuf = DefaultAllocator.allocate(cap)
        buf.writeByteArray(bytes, 0, bytes.size)
        val hash = HttpHeaders.caseInsensitiveHashOfBuf(buf, 0, name.length)
        val h = HttpHeaders.borrow()
        h.addRange(buf, hash, 0, name.length, name.length, value.length)
        assertFalse(
            buf.release(),
            "addRange did not retain the buffer — capacity guard degraded '$name: $value' to a string entry",
        )
        return h
    }

    /** Runs [block], then pins parity with the String reference and releases. */
    private fun withRangeContentLength(value: String, block: (HttpHeaders) -> Unit) {
        val h = rangeHeaderOf("Content-Length", value)
        try {
            block(h)
            assertEquals(
                h.getString(HttpHeaderName.CONTENT_LENGTH_KEY)?.trim()?.toLongOrNull(),
                h.contentLength,
                "range-slot contentLength diverged from the String reference for value='$value'",
            )
        } finally {
            h.release()
        }
    }

    @Test
    fun `range slot parses plain decimal value in place`() {
        withRangeContentLength("42") { h ->
            assertEquals(42L, h.contentLength)
        }
    }

    @Test
    fun `range slot trims surrounding whitespace like the String path`() {
        withRangeContentLength(" \t42\t ") { h ->
            assertEquals(42L, h.contentLength)
        }
    }

    @Test
    fun `range slot returns null for malformed value`() {
        withRangeContentLength("12abc") { h ->
            assertNull(h.contentLength)
        }
    }

    @Test
    fun `range slot returns null for empty and whitespace-only values`() {
        withRangeContentLength("") { h ->
            assertNull(h.contentLength)
        }
        withRangeContentLength("   ") { h ->
            assertNull(h.contentLength)
        }
    }

    @Test
    fun `range slot preserves sign handling of the String path`() {
        // RFC 7230 Content-Length is 1*DIGIT; sign acceptance mirrors the
        // previous String.toLongOrNull semantics, and the decoder's
        // `cl > 0` framing guard treats non-positive values as "no body".
        withRangeContentLength("+5") { h ->
            assertEquals(5L, h.contentLength)
        }
        withRangeContentLength("-5") { h ->
            assertEquals(-5L, h.contentLength)
        }
    }

    @Test
    fun `range slot parses Long MAX_VALUE and nulls on overflow`() {
        withRangeContentLength("9223372036854775807") { h ->
            assertEquals(Long.MAX_VALUE, h.contentLength)
        }
        withRangeContentLength("9223372036854775808") { h ->
            assertNull(h.contentLength)
        }
    }

    @Test
    fun `duplicate Content-Length keeps first-value semantics`() {
        val h = rangeHeaderOf("Content-Length", "11")
        // Second entry lands in a separate buffer of the same capacity —
        // the multi-segment chain accepts it as an extra backing.
        val bytes = ("Content-Length" + "22").encodeToByteArray()
        var cap = 1
        while (cap < bytes.size) cap = cap shl 1
        val buf2: IoBuf = DefaultAllocator.allocate(cap)
        buf2.writeByteArray(bytes, 0, bytes.size)
        h.addRange(
            buf2,
            HttpHeaders.caseInsensitiveHashOfBuf(buf2, 0, "Content-Length".length),
            0,
            "Content-Length".length,
            "Content-Length".length,
            2,
        )
        assertFalse(buf2.release())
        try {
            assertEquals(
                h.getString(HttpHeaderName.CONTENT_LENGTH_KEY)?.trim()?.toLongOrNull(),
                h.contentLength,
            )
        } finally {
            h.release()
        }
    }

    @Test
    fun `string entry keeps the trim plus parse semantics`() {
        val h = HttpHeaders.borrow()
        h.add("Content-Length", "  42  ")
        assertEquals(42L, h.contentLength)
        h.release()
    }

    @Test
    fun `isChunked reads the range slot without materialising`() {
        val h = rangeHeaderOf("Transfer-Encoding", "chunked")
        assertTrue(h.isChunked)
        h.release()
    }

    @Test
    fun `isChunked matches token case-insensitively inside a list`() {
        val h = rangeHeaderOf("Transfer-Encoding", "gzip, CHUNKED")
        assertTrue(h.isChunked)
        h.release()
    }

    @Test
    fun `isChunked false for non-chunked encoding and absent header`() {
        val h = rangeHeaderOf("Transfer-Encoding", "gzip")
        assertFalse(h.isChunked)
        h.release()

        val empty = HttpHeaders.borrow()
        assertFalse(empty.isChunked)
        empty.release()
    }
}
