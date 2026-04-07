package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpResponseTest {

    @Test
    fun `default version is HTTP 1_1`() {
        val r = HttpResponse(HttpStatus.OK)
        assertEquals(HttpVersion.HTTP_1_1, r.version)
    }

    @Test
    fun `status code accessible`() {
        val r = HttpResponse(HttpStatus.OK)
        assertEquals(200, r.status.code)
    }

    @Test
    fun `body null by default`() {
        val r = HttpResponse(HttpStatus.NO_CONTENT)
        assertNull(r.body)
    }

    @Test
    fun `body stored when provided`() {
        val body = "hello".encodeToByteArray()
        val r = HttpResponse(HttpStatus.OK, body = body)
        assertTrue(body.contentEquals(r.body!!))
    }

    @Test
    fun `headers accessible`() {
        val h = HttpHeaders().add("Content-Type", "text/plain")
        val r = HttpResponse(HttpStatus.OK, headers = h)
        assertEquals("text/plain", r.headers["Content-Type"])
    }

    // --- Factory: ok(String?) ---

    @Test
    fun `ok with text body`() {
        val r = HttpResponse.ok("hello")
        assertEquals(HttpStatus.OK, r.status)
        assertEquals("text/plain", r.headers["Content-Type"])
        assertEquals("5", r.headers["Content-Length"])
        assertTrue("hello".encodeToByteArray().contentEquals(r.body!!))
    }

    @Test
    fun `ok with null body`() {
        val r = HttpResponse.ok()
        assertEquals(HttpStatus.OK, r.status)
        assertEquals("0", r.headers["Content-Length"])
        assertNull(r.body)
    }

    @Test
    fun `ok with custom content type`() {
        val r = HttpResponse.ok("data", contentType = "application/json")
        assertEquals("application/json", r.headers["Content-Type"])
    }

    // --- Factory: ok(ByteArray) ---

    @Test
    fun `ok with binary body`() {
        val bytes = byteArrayOf(1, 2, 3)
        val r = HttpResponse.ok(bytes)
        assertEquals("application/octet-stream", r.headers["Content-Type"])
        assertEquals("3", r.headers["Content-Length"])
        assertTrue(bytes.contentEquals(r.body!!))
    }

    // --- Factory: notFound ---

    @Test
    fun `notFound factory`() {
        val r = HttpResponse.notFound("not here")
        assertEquals(HttpStatus.NOT_FOUND, r.status)
        assertEquals("text/plain", r.headers["Content-Type"])
        assertTrue("not here".encodeToByteArray().contentEquals(r.body!!))
    }

    @Test
    fun `notFound with null body`() {
        val r = HttpResponse.notFound()
        assertEquals(HttpStatus.NOT_FOUND, r.status)
        assertNull(r.body)
    }

    // --- Factory: of ---

    @Test
    fun `of factory with status and body`() {
        val r = HttpResponse.of(HttpStatus.CREATED, "created")
        assertEquals(HttpStatus.CREATED, r.status)
        assertTrue("created".encodeToByteArray().contentEquals(r.body!!))
    }

    // --- data class: copy ---

    @Test
    fun `copy preserves fields`() {
        val original = HttpResponse(HttpStatus.OK, body = "hello".encodeToByteArray())
        val copied = original.copy(status = HttpStatus.NOT_FOUND)
        assertEquals(HttpStatus.NOT_FOUND, copied.status)
        assertTrue("hello".encodeToByteArray().contentEquals(copied.body!!))
    }

    // --- equals / hashCode ---

    @Test
    fun `equals with same body content`() {
        val a = HttpResponse(HttpStatus.OK, body = "hello".encodeToByteArray())
        val b = HttpResponse(HttpStatus.OK, body = "hello".encodeToByteArray())
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `equals with different body content`() {
        val a = HttpResponse(HttpStatus.OK, body = "hello".encodeToByteArray())
        val b = HttpResponse(HttpStatus.OK, body = "world".encodeToByteArray())
        assertNotEquals(a, b)
    }
}
