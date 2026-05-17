package io.github.fukusaka.keel.server.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/** Unit tests for the RFC 9110 IMF-fixdate formatter / parser. */
class HttpDateTest {

    @Test
    fun `the epoch formats to the IMF-fixdate Thursday`() {
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", formatHttpDate(Instant.fromEpochSeconds(0)))
    }

    @Test
    fun `a known instant formats to the RFC 9110 example date`() {
        // RFC 9110 §5.6.7 worked example.
        val instant = Instant.fromEpochSeconds(784_111_777L)
        assertEquals("Sun, 06 Nov 1994 08:49:37 GMT", formatHttpDate(instant))
    }

    @Test
    fun `formatting then parsing round-trips`() {
        val instant = Instant.fromEpochSeconds(1_700_000_000L)
        assertEquals(instant, parseHttpDate(formatHttpDate(instant)))
    }

    @Test
    fun `parsing the RFC 9110 example yields the expected instant`() {
        assertEquals(Instant.fromEpochSeconds(784_111_777L), parseHttpDate("Sun, 06 Nov 1994 08:49:37 GMT"))
    }

    @Test
    fun `a malformed date string parses to null`() {
        assertNull(parseHttpDate("not a date"))
        assertNull(parseHttpDate("Sun, 06 Xyz 1994 08:49:37 GMT"))
        assertNull(parseHttpDate("Sun, 06 Nov 1994 08:49:37 UTC"))
    }
}
