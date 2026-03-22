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
        assertFailsWith<IllegalArgumentException> { HttpVersion.of("HTTP/2.0") }
    }

    @Test
    fun ofLowercaseThrows() {
        assertFailsWith<IllegalArgumentException> { HttpVersion.of("http/1.1") }
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
}
