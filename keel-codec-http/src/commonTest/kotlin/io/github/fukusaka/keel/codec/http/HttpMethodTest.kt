package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HttpMethodTest {

    @Test
    fun equalityByName() {
        assertEquals(HttpMethod.GET, HttpMethod("GET"))
    }

    @Test
    fun caseSensitive() {
        assertNotEquals(HttpMethod.GET, HttpMethod("get"))
    }

    @Test
    fun customMethodAllowed() {
        val m = HttpMethod("PROPFIND")
        assertEquals("PROPFIND", m.name)
    }

    @Test
    fun emptyNameThrows() {
        assertFailsWith<IllegalArgumentException> { HttpMethod("") }
    }

    @Test
    fun whitespaceNameThrows() {
        assertFailsWith<IllegalArgumentException> { HttpMethod("GET POST") }
    }

    @Test
    fun toStringReturnsName() {
        assertEquals("GET", HttpMethod.GET.toString())
        assertEquals("POST", HttpMethod.POST.toString())
    }

    @Test
    fun safeMethodsCorrect() {
        assertTrue(HttpMethod.GET.isSafe)
        assertTrue(HttpMethod.HEAD.isSafe)
        assertTrue(HttpMethod.OPTIONS.isSafe)
        assertTrue(HttpMethod.TRACE.isSafe)
        assertFalse(HttpMethod.POST.isSafe)
        assertFalse(HttpMethod.PUT.isSafe)
        assertFalse(HttpMethod.DELETE.isSafe)
        assertFalse(HttpMethod.PATCH.isSafe)
    }

    @Test
    fun idempotentMethodsCorrect() {
        assertTrue(HttpMethod.GET.isIdempotent)
        assertTrue(HttpMethod.HEAD.isIdempotent)
        assertTrue(HttpMethod.PUT.isIdempotent)
        assertTrue(HttpMethod.DELETE.isIdempotent)
        assertTrue(HttpMethod.OPTIONS.isIdempotent)
        assertTrue(HttpMethod.TRACE.isIdempotent)
        assertFalse(HttpMethod.POST.isIdempotent)
        assertFalse(HttpMethod.PATCH.isIdempotent)
    }

    // --- fromBytesOrNull (ByteArray overload) ---
    //
    // Covers every cached standard method + unknown token null return +
    // offset non-zero + length mismatch edge cases. The IoBuf overload is
    // exercised via the HttpRequestDecoder integration tests so it is not
    // duplicated here.

    @Test
    fun fromBytesOrNullReturnsCachedForStandardMethods() {
        assertSame(HttpMethod.GET, HttpMethod.fromBytesOrNull("GET".encodeToByteArray(), 0, 3))
        assertSame(HttpMethod.PUT, HttpMethod.fromBytesOrNull("PUT".encodeToByteArray(), 0, 3))
        assertSame(HttpMethod.POST, HttpMethod.fromBytesOrNull("POST".encodeToByteArray(), 0, 4))
        assertSame(HttpMethod.HEAD, HttpMethod.fromBytesOrNull("HEAD".encodeToByteArray(), 0, 4))
        assertSame(HttpMethod.PATCH, HttpMethod.fromBytesOrNull("PATCH".encodeToByteArray(), 0, 5))
        assertSame(HttpMethod.TRACE, HttpMethod.fromBytesOrNull("TRACE".encodeToByteArray(), 0, 5))
        assertSame(HttpMethod.DELETE, HttpMethod.fromBytesOrNull("DELETE".encodeToByteArray(), 0, 6))
        assertSame(HttpMethod.OPTIONS, HttpMethod.fromBytesOrNull("OPTIONS".encodeToByteArray(), 0, 7))
        assertSame(HttpMethod.CONNECT, HttpMethod.fromBytesOrNull("CONNECT".encodeToByteArray(), 0, 7))
    }

    @Test
    fun fromBytesOrNullHonorsOffset() {
        // Prefix + token + suffix — lookup uses only [offset, offset+length).
        val buf = "##GET##".encodeToByteArray()
        assertSame(HttpMethod.GET, HttpMethod.fromBytesOrNull(buf, 2, 3))
    }

    @Test
    fun fromBytesOrNullReturnsNullForExtensionMethod() {
        // PROPFIND is 8 bytes — outside the cached length set entirely.
        assertNull(HttpMethod.fromBytesOrNull("PROPFIND".encodeToByteArray(), 0, 8))
    }

    @Test
    fun fromBytesOrNullReturnsNullForUnknownTokenOfMatchingLength() {
        // FOO is 3 bytes long but does not match GET or PUT.
        assertNull(HttpMethod.fromBytesOrNull("FOO".encodeToByteArray(), 0, 3))
        // 4-byte non-match.
        assertNull(HttpMethod.fromBytesOrNull("XYZW".encodeToByteArray(), 0, 4))
        // 6-byte non-match (DELETE is the only 6-byte cached method).
        assertNull(HttpMethod.fromBytesOrNull("DELETX".encodeToByteArray(), 0, 6))
    }

    @Test
    fun fromBytesOrNullIsCaseSensitive() {
        // Lowercase must miss, consistent with HttpMethod.of() case semantics.
        assertNull(HttpMethod.fromBytesOrNull("get".encodeToByteArray(), 0, 3))
        assertNull(HttpMethod.fromBytesOrNull("Get".encodeToByteArray(), 0, 3))
    }

    @Test
    fun fromBytesOrNullReturnsNullForZeroLength() {
        assertNull(HttpMethod.fromBytesOrNull(ByteArray(0), 0, 0))
    }
}
