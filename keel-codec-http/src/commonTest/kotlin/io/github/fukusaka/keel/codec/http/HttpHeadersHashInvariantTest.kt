package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBufAsciiText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `caseInsensitiveHash(CharSequence)` and `caseInsensitiveHashOfBuf(IoBuf,
 * start, length)` must produce the same hash for the same ASCII byte
 * sequence. Without this invariant a name parsed straight off the recv
 * buffer (the range-entry path) would land in a different
 * hash bucket than the same name materialised to a `String` for lookup,
 * silently breaking every header lookup the codec performs.
 *
 * The previous PR (#599 chain-global addressing) exposed the
 * `IoBufAsciiText` view as a first-class `CharSequence` and the lookup
 * API now accepts a `CharSequence` key directly, so the bytewise hash
 * of the underlying buffer and the `CharSequence`-iterating hash of the
 * `IoBufAsciiText` view of those same bytes must coincide.
 *
 * Pinning the invariant in a test prevents either implementation from
 * drifting (e.g. a future polynomial change applied to only one path).
 */
class HttpHeadersHashInvariantTest {

    @Test
    fun `caseInsensitiveHash of CharSequence matches caseInsensitiveHashOfBuf for ASCII`() {
        // Cover well-known header names, mixed case, hyphenated, single
        // char, multi-token. Numeric and special chars are valid in
        // tchar (RFC 7230 §3.2.6) so include them too.
        val names = listOf(
            "Content-Type",
            "host",
            "ACCEPT-ENCODING",
            "X-Trace-Id",
            "A",
            "x-1",
            "If-None-Match",
            "X-Custom-Header-With-Several-Tokens",
            "!#$%&'*+-.^_`|~",
        )
        for (name in names) {
            val bytes = name.encodeToByteArray()
            val buf = DefaultAllocator.allocate(64.coerceAtLeast(bytes.size))
            try {
                buf.writeByteArray(bytes, 0, bytes.size)
                val viaString = HttpHeaders.caseInsensitiveHash(name)
                val viaView = HttpHeaders.caseInsensitiveHash(IoBufAsciiText(buf, 0, bytes.size))
                val viaBuf = HttpHeaders.caseInsensitiveHashOfBuf(buf, 0, bytes.size)
                assertEquals(
                    viaString,
                    viaBuf,
                    "String vs Buf hash diverged for `$name`",
                )
                assertEquals(
                    viaString,
                    viaView,
                    "String vs IoBufAsciiText hash diverged for `$name`",
                )
            } finally {
                buf.release()
            }
        }
    }

    @Test
    fun `case folding is symmetric across all three hash paths`() {
        // Upper-case vs lower-case ASCII must fold to the same hash on
        // every path (the folding is A..Z → a..z; non-ASCII bytes
        // unaffected, but header names are ASCII-only per RFC 7230).
        val pairs = listOf(
            "Content-Type" to "content-type",
            "HOST" to "host",
            "X-Trace-Id" to "x-trace-id",
            "ACCEPT" to "Accept",
        )
        for ((upper, lower) in pairs) {
            val upperBytes = upper.encodeToByteArray()
            val lowerBytes = lower.encodeToByteArray()
            val ubuf = DefaultAllocator.allocate(64)
            val lbuf = DefaultAllocator.allocate(64)
            try {
                ubuf.writeByteArray(upperBytes, 0, upperBytes.size)
                lbuf.writeByteArray(lowerBytes, 0, lowerBytes.size)
                val uString = HttpHeaders.caseInsensitiveHash(upper)
                val lString = HttpHeaders.caseInsensitiveHash(lower)
                val uBuf = HttpHeaders.caseInsensitiveHashOfBuf(ubuf, 0, upperBytes.size)
                val lBuf = HttpHeaders.caseInsensitiveHashOfBuf(lbuf, 0, lowerBytes.size)
                val uView = HttpHeaders.caseInsensitiveHash(IoBufAsciiText(ubuf, 0, upperBytes.size))
                val lView = HttpHeaders.caseInsensitiveHash(IoBufAsciiText(lbuf, 0, lowerBytes.size))
                assertEquals(uString, lString, "String folding asymmetric: $upper vs $lower")
                assertEquals(uBuf, lBuf, "Buf folding asymmetric: $upper vs $lower")
                assertEquals(uView, lView, "View folding asymmetric: $upper vs $lower")
                assertEquals(uString, uBuf, "String vs Buf hash diverged: $upper")
                assertEquals(uString, uView, "String vs View hash diverged: $upper")
            } finally {
                ubuf.release()
                lbuf.release()
            }
        }
    }

    @Test
    fun `empty name hashes to zero across all three paths`() {
        val buf = DefaultAllocator.allocate(16)
        try {
            assertEquals(0, HttpHeaders.caseInsensitiveHash(""))
            assertEquals(0, HttpHeaders.caseInsensitiveHashOfBuf(buf, 0, 0))
            assertEquals(0, HttpHeaders.caseInsensitiveHash(IoBufAsciiText(buf, 0, 0)))
        } finally {
            buf.release()
        }
    }
}
