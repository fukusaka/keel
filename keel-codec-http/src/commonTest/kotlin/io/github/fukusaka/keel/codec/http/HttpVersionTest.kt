package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HttpVersionTest {

    @Test
    fun ofHttpOneZero() {
        assertEquals(HttpVersion.HTTP_1_0, HttpVersion.of("HTTP/1.0"))
    }

    @Test
    fun ofHttpOneOne() {
        assertEquals(HttpVersion.HTTP_1_1, HttpVersion.of("HTTP/1.1"))
    }

    @Test
    fun ofUnknownThrows() {
        assertFailsWith<HttpParseException> { HttpVersion.of("HTTP/2.0") }
    }

    @Test
    fun ofLowercaseThrows() {
        assertFailsWith<HttpParseException> { HttpVersion.of("http/1.1") }
    }

    @Test
    fun textProperty() {
        assertEquals("HTTP/1.1", HttpVersion.HTTP_1_1.text)
        assertEquals("HTTP/1.0", HttpVersion.HTTP_1_0.text)
    }

    @Test
    fun majorMinorProperties() {
        assertEquals(1, HttpVersion.HTTP_1_1.major)
        assertEquals(1, HttpVersion.HTTP_1_1.minor)
        assertEquals(1, HttpVersion.HTTP_1_0.major)
        assertEquals(0, HttpVersion.HTTP_1_0.minor)
    }

    // --- fromBytes (ByteArray overload) ---
    //
    // Covers both valid versions, offset handling, and the error message
    // parity with of(String). The IoBuf overload is exercised via the
    // HttpRequestDecoder integration tests so it is not duplicated here.

    @Test
    fun fromBytesHttpOneOne() {
        assertEquals(HttpVersion.HTTP_1_1, HttpVersion.fromBytes("HTTP/1.1".encodeToByteArray(), 0, 8))
    }

    @Test
    fun fromBytesHttpOneZero() {
        assertEquals(HttpVersion.HTTP_1_0, HttpVersion.fromBytes("HTTP/1.0".encodeToByteArray(), 0, 8))
    }

    @Test
    fun fromBytesHonorsOffset() {
        val buf = "##HTTP/1.1##".encodeToByteArray()
        assertEquals(HttpVersion.HTTP_1_1, HttpVersion.fromBytes(buf, 2, 8))
    }

    @Test
    fun fromBytesUnknownVersionThrows() {
        val ex = assertFailsWith<HttpParseException> {
            HttpVersion.fromBytes("HTTP/2.0".encodeToByteArray(), 0, 8)
        }
        assertEquals("Unsupported HTTP version: HTTP/2.0", ex.message)
    }

    @Test
    fun fromBytesLengthMismatchThrows() {
        // Missing trailing digit — length 7 cannot match the 8-byte prefix check.
        assertFailsWith<HttpParseException> {
            HttpVersion.fromBytes("HTTP/1.".encodeToByteArray(), 0, 7)
        }
        // Longer than 8 — also rejected.
        assertFailsWith<HttpParseException> {
            HttpVersion.fromBytes("HTTP/1.10".encodeToByteArray(), 0, 9)
        }
    }

    @Test
    fun fromBytesLowercaseThrows() {
        // Consistent with of("http/1.1") rejection.
        assertFailsWith<HttpParseException> {
            HttpVersion.fromBytes("http/1.1".encodeToByteArray(), 0, 8)
        }
    }
}
