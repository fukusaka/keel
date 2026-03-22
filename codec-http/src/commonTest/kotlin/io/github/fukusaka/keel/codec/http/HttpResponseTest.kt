package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpResponseTest {

    @Test
    fun defaultVersionIsHttp11() {
        val r = HttpResponse(HttpStatus.OK)
        assertEquals(HttpVersion.HTTP_1_1, r.version)
    }

    @Test
    fun statusCodeAccessible() {
        val r = HttpResponse(HttpStatus.OK)
        assertEquals(200, r.status.code)
    }

    @Test
    fun bodyNullByDefault() {
        val r = HttpResponse(HttpStatus.NO_CONTENT)
        assertNull(r.body)
    }

    @Test
    fun bodyStoredWhenProvided() {
        val body = "hello".encodeToByteArray()
        val r = HttpResponse(HttpStatus.OK, body = body)
        assertTrue(body.contentEquals(r.body!!))
    }

    @Test
    fun headersAccessible() {
        val h = HttpHeaders().add("Content-Type", "text/plain")
        val r = HttpResponse(HttpStatus.OK, headers = h)
        assertEquals("text/plain", r.headers["Content-Type"])
    }
}
