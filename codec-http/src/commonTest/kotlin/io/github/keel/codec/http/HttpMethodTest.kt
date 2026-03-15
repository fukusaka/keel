package io.github.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
}
