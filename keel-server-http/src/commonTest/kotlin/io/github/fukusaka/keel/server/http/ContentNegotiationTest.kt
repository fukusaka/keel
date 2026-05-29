package io.github.fukusaka.keel.server.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for the [parseAcceptHeader] / [scoreProducedType] content
 * negotiation core (RFC 9110 §12.5.1) backing the Router's `produces`
 * best-match (router R-5).
 */
class ContentNegotiationTest {

    private fun score(producedType: String, accept: String): Int =
        scoreProducedType(producedType, parseAcceptHeader(accept) ?: error("accept was null"))

    @Test
    fun `absent or blank Accept parses to null so anything is acceptable`() {
        assertNull(parseAcceptHeader(null))
        assertNull(parseAcceptHeader(""))
        assertNull(parseAcceptHeader("   "))
    }

    @Test
    fun `exact type match is acceptable`() {
        assertTrue(score("application/json", "application/json") > NOT_ACCEPTABLE_SCORE)
    }

    @Test
    fun `unlisted type with no wildcard is not acceptable`() {
        assertEquals(NOT_ACCEPTABLE_SCORE, score("text/html", "application/json"))
    }

    @Test
    fun `wildcard ranges match`() {
        assertTrue(score("application/json", "*/*") > NOT_ACCEPTABLE_SCORE)
        assertTrue(score("application/json", "application/*") > NOT_ACCEPTABLE_SCORE)
        assertEquals(NOT_ACCEPTABLE_SCORE, score("text/html", "application/*"))
    }

    @Test
    fun `q equals zero refuses an otherwise-matching type`() {
        assertEquals(NOT_ACCEPTABLE_SCORE, score("application/json", "application/json;q=0"))
        // Explicit refusal of a type still allows others via a wildcard.
        assertTrue(score("text/html", "application/json;q=0, */*") > NOT_ACCEPTABLE_SCORE)
    }

    @Test
    fun `higher q wins across candidate produced types`() {
        val accept = "application/json;q=0.4, text/html;q=0.9"
        assertTrue(score("text/html", accept) > score("application/json", accept))
    }

    @Test
    fun `most specific matching range determines q`() {
        // json matched by the specific application/json (q=0.2); html only
        // by */* (q=0.9). The specific lower-q range applies to json, so
        // html (via */* q=0.9) outranks json (via exact q=0.2).
        val accept = "application/json;q=0.2, */*;q=0.9"
        assertTrue(score("text/html", accept) > score("application/json", accept))
        // Both are acceptable, though.
        assertTrue(score("application/json", accept) > NOT_ACCEPTABLE_SCORE)
    }

    @Test
    fun `specificity breaks q ties`() {
        // Same q=1 for both ranges; the exact match is more specific than */*.
        val ranges = parseAcceptHeader("application/json, */*") ?: error("null")
        assertTrue(score("application/json", "application/json, */*") > score("text/html", "application/json, */*"))
        assertTrue(scoreProducedType("text/html", ranges) > NOT_ACCEPTABLE_SCORE)
    }

    @Test
    fun `malformed elements are skipped while valid ones are kept`() {
        // "garbage" (no slash) and "/json" (empty type) are skipped; the
        // valid application/json remains.
        assertTrue(score("application/json", "garbage, /json, application/json") > NOT_ACCEPTABLE_SCORE)
        assertEquals(NOT_ACCEPTABLE_SCORE, score("text/html", "garbage, /json, application/json"))
    }

    @Test
    fun `fully unparseable header yields an empty list so nothing is acceptable`() {
        val ranges = parseAcceptHeader("garbage, also-garbage")
        assertEquals(emptyList(), ranges?.map { it.type })
        assertEquals(NOT_ACCEPTABLE_SCORE, scoreProducedType("application/json", ranges!!))
    }

    @Test
    fun `parsing is case-insensitive on type and subtype`() {
        assertTrue(score("application/json", "APPLICATION/JSON") > NOT_ACCEPTABLE_SCORE)
    }
}
