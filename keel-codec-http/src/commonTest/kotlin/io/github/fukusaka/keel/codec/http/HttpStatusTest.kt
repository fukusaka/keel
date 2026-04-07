package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpStatusTest {

    @Test
    fun categoryClassification() {
        assertTrue(HttpStatus(100).isInformational)
        assertFalse(HttpStatus(100).isSuccess)

        assertTrue(HttpStatus(200).isSuccess)
        assertFalse(HttpStatus(200).isInformational)

        assertTrue(HttpStatus(301).isRedirection)
        assertFalse(HttpStatus(301).isSuccess)

        assertTrue(HttpStatus(404).isClientError)
        assertFalse(HttpStatus(404).isServerError)

        assertTrue(HttpStatus(500).isServerError)
        assertFalse(HttpStatus(500).isClientError)
    }

    @Test
    fun wellKnownReasonPhrases() {
        assertEquals("OK", HttpStatus.OK.reasonPhrase())
        assertEquals("Not Found", HttpStatus.NOT_FOUND.reasonPhrase())
        assertEquals("Internal Server Error", HttpStatus.INTERNAL_SERVER_ERROR.reasonPhrase())
        assertEquals("No Content", HttpStatus.NO_CONTENT.reasonPhrase())
        assertEquals("Created", HttpStatus.CREATED.reasonPhrase())
    }

    @Test
    fun unknownCodeReasonPhrase() {
        assertEquals("Unknown", HttpStatus(999).reasonPhrase())
        assertEquals("Unknown", HttpStatus(299).reasonPhrase())
    }

    @Test
    fun customCodeAllowed() {
        val s = HttpStatus(599)
        assertEquals(599, s.code)
        assertTrue(s.isServerError)
    }

    @Test
    fun invalidCodeThrows() {
        assertFailsWith<IllegalArgumentException> { HttpStatus(99) }
        assertFailsWith<IllegalArgumentException> { HttpStatus(1000) }
    }

    @Test
    fun toStringFormat() {
        assertEquals("200 OK", HttpStatus.OK.toString())
        assertEquals("404 Not Found", HttpStatus.NOT_FOUND.toString())
    }

    @Test
    fun equalityByCode() {
        assertEquals(HttpStatus(200), HttpStatus.OK)
        assertEquals(HttpStatus(404), HttpStatus.NOT_FOUND)
    }
}
