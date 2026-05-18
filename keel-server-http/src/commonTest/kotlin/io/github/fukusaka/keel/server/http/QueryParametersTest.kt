package io.github.fukusaka.keel.server.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Unit tests for [parseQueryParameters]. */
class QueryParametersTest {

    @Test
    fun `a null query string yields an empty map`() {
        assertTrue(parseQueryParameters(null).isEmpty())
    }

    @Test
    fun `an empty query string yields an empty map`() {
        assertTrue(parseQueryParameters("").isEmpty())
    }

    @Test
    fun `name=value pairs split on ampersand`() {
        assertEquals(mapOf("a" to "1", "b" to "2"), parseQueryParameters("a=1&b=2"))
    }

    @Test
    fun `a repeated key keeps its first value`() {
        assertEquals(mapOf("a" to "1"), parseQueryParameters("a=1&a=2"))
    }

    @Test
    fun `a key with no equals maps to the empty string`() {
        assertEquals(mapOf("flag" to "", "a" to "1"), parseQueryParameters("flag&a=1"))
    }

    @Test
    fun `a value may itself contain an equals sign`() {
        assertEquals(mapOf("e" to "a=b"), parseQueryParameters("e=a=b"))
    }

    @Test
    fun `an empty pair between ampersands is skipped`() {
        assertEquals(mapOf("a" to "1", "b" to "2"), parseQueryParameters("a=1&&b=2"))
    }

    @Test
    fun `plus is decoded to a space in name and value`() {
        assertEquals(mapOf("full name" to "John Doe"), parseQueryParameters("full+name=John+Doe"))
    }

    @Test
    fun `percent escapes are decoded as UTF-8`() {
        assertEquals(mapOf("q" to "a&b"), parseQueryParameters("q=a%26b"))
        assertEquals(mapOf("q" to "é"), parseQueryParameters("q=%C3%A9"))
    }

    @Test
    fun `a malformed percent escape is kept literal`() {
        assertEquals(mapOf("q" to "100%"), parseQueryParameters("q=100%"))
        assertEquals(mapOf("q" to "%zz"), parseQueryParameters("q=%zz"))
        assertEquals(mapOf("q" to "%1"), parseQueryParameters("q=%1"))
    }

    @Test
    fun `insertion order is preserved`() {
        assertEquals(listOf("z", "a", "m"), parseQueryParameters("z=1&a=2&m=3").keys.toList())
    }
}
