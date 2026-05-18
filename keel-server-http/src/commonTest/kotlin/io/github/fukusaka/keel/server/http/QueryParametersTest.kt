package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.server.http.dsl.QueryParameterConfigBuilder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Unit tests for [parseQueryParameters] and [QueryParameters]. */
class QueryParametersTest {

    private fun parse(qs: String?, config: QueryParameterConfig = QueryParameterConfig.DEFAULT): QueryParameters =
        parseQueryParameters(qs, config)

    @Test
    fun `a null query string yields the empty instance`() {
        assertTrue(parse(null).isEmpty)
        assertEquals(QueryParameters.EMPTY, parse(null))
    }

    @Test
    fun `an empty query string yields the empty instance`() {
        assertTrue(parse("").isEmpty)
    }

    @Test
    fun `name=value pairs split on ampersand`() {
        val q = parse("a=1&b=2")
        assertEquals("1", q["a"])
        assertEquals("2", q["b"])
    }

    @Test
    fun `get returns the first value of a repeated key`() {
        assertEquals("1", parse("a=1&a=2")["a"])
    }

    @Test
    fun `getAll returns every value of a repeated key in order`() {
        assertEquals(listOf("1", "2", "3"), parse("a=1&a=2&a=3").getAll("a"))
    }

    @Test
    fun `getAll returns an empty list for an absent name`() {
        assertEquals(emptyList(), parse("a=1").getAll("missing"))
    }

    @Test
    fun `get returns null for an absent name`() {
        assertNull(parse("a=1")["missing"])
    }

    @Test
    fun `contains reports name presence`() {
        val q = parse("a=1")
        assertTrue("a" in q)
        assertFalse("b" in q)
    }

    @Test
    fun `names lists the distinct names in first-appearance order`() {
        assertEquals(listOf("z", "a", "m"), parse("z=1&a=2&z=3&m=4").names.toList())
    }

    @Test
    fun `size counts duplicates`() {
        assertEquals(4, parse("a=1&a=2&b=3&c=4").size)
    }

    @Test
    fun `a key with no equals maps to the empty string`() {
        assertEquals("", parse("flag&a=1")["flag"])
    }

    @Test
    fun `a value may itself contain an equals sign`() {
        assertEquals("a=b", parse("e=a=b")["e"])
    }

    @Test
    fun `a semicolon is a literal value character not a separator`() {
        assertEquals("1;2", parse("a=1;2")["a"])
    }

    @Test
    fun `an empty pair between ampersands is skipped`() {
        val q = parse("a=1&&b=2")
        assertEquals("1", q["a"])
        assertEquals("2", q["b"])
        assertEquals(2, q.size)
    }

    @Test
    fun `plus is decoded to a space in name and value`() {
        assertEquals("John Doe", parse("full+name=John+Doe")["full name"])
    }

    @Test
    fun `percent escapes are decoded as UTF-8`() {
        assertEquals("a&b", parse("q=a%26b")["q"])
        assertEquals("é", parse("q=%C3%A9")["q"])
    }

    @Test
    fun `a malformed percent escape is kept literal in lenient mode`() {
        assertEquals("100%", parse("q=100%")["q"])
        assertEquals("%zz", parse("q=%zz")["q"])
        assertEquals("%1", parse("q=%1")["q"])
    }

    @Test
    fun `a query string exceeding maxParameterCount throws`() {
        val config = QueryParameterConfigBuilder().apply { maxParameterCount = 3 }.build()
        assertFailsWith<MalformedQueryStringException> { parse("a=1&b=2&c=3&d=4", config) }
    }

    @Test
    fun `a query string at maxParameterCount is accepted`() {
        val config = QueryParameterConfigBuilder().apply { maxParameterCount = 3 }.build()
        assertEquals(3, parse("a=1&b=2&c=3", config).size)
    }

    @Test
    fun `a control character is rejected when rejectControlCharacters is set`() {
        val config = QueryParameterConfigBuilder().apply { rejectControlCharacters = true }.build()
        assertFailsWith<MalformedQueryStringException> { parse("q=bad%00value", config) }
    }

    @Test
    fun `a control character is kept when rejectControlCharacters is off`() {
        assertEquals("bad\u0000value", parse("q=bad%00value")["q"])
    }

    @Test
    fun `a malformed percent escape is rejected when rejectMalformedEncoding is set`() {
        val config = QueryParameterConfigBuilder().apply { rejectMalformedEncoding = true }.build()
        assertFailsWith<MalformedQueryStringException> { parse("q=100%", config) }
    }

    @Test
    fun `invalid UTF-8 is rejected when rejectMalformedEncoding is set`() {
        // %C3 is a lone UTF-8 lead byte with no continuation byte.
        val config = QueryParameterConfigBuilder().apply { rejectMalformedEncoding = true }.build()
        assertFailsWith<MalformedQueryStringException> { parse("q=%C3", config) }
    }

    @Test
    fun `invalid UTF-8 is replaced with U+FFFD when rejectMalformedEncoding is off`() {
        val value = assertNotNull(parse("q=%C3")["q"])
        assertTrue(value.contains('�'))
    }
}
