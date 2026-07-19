package io.github.fukusaka.keel.codec.http

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for the shared HTTP weight (q-value) parse [weightMillisOf]
 * (RFC 9110 §12.4.2), the primitive both `Accept` (the router's
 * `produces` selection) and `Accept-Encoding` negotiation build on.
 * Pure synchronous logic.
 *
 * The input is an element already split on `;`, with `params[0]` the token
 * (ignored here) and any `q=` weight in a later part.
 */
class WeightTest {

    @Test
    fun `absent q defaults to 1000 milli`() {
        assertEquals(WEIGHT_MILLI, weightMillisOf(listOf("text/html")))
        assertEquals(WEIGHT_MILLI, weightMillisOf(listOf("gzip", "charset=utf-8")))
    }

    @Test
    fun `q is scaled to thousandths`() {
        assertEquals(1000, weightMillisOf(listOf("x", "q=1.0")))
        assertEquals(900, weightMillisOf(listOf("x", "q=0.9")))
        assertEquals(40, weightMillisOf(listOf("x", "q=0.04")))
        assertEquals(0, weightMillisOf(listOf("x", "q=0")))
    }

    @Test
    fun `q is clamped to the 0 to 1 range`() {
        assertEquals(WEIGHT_MILLI, weightMillisOf(listOf("x", "q=2.5")))
        assertEquals(0, weightMillisOf(listOf("x", "q=-1")))
    }

    @Test
    fun `q parsing is case-insensitive on the parameter name and tolerates whitespace`() {
        assertEquals(500, weightMillisOf(listOf("x", " Q=0.5 ")))
    }

    @Test
    fun `an unparseable q is treated as absent`() {
        assertEquals(WEIGHT_MILLI, weightMillisOf(listOf("x", "q=high")))
    }

    @Test
    fun `the first q parameter wins`() {
        assertEquals(300, weightMillisOf(listOf("x", "q=0.3", "q=0.9")))
    }
}
