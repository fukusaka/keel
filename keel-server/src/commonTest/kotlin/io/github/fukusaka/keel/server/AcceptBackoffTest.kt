package io.github.fukusaka.keel.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * Pins the data-class shape and default values of [AcceptBackoff]. The
 * runtime semantics (reset on success, exponentiation, max cap) are covered
 * by [AcceptLoopTest] against a mocked [io.github.fukusaka.keel.core.StreamServer].
 */
class AcceptBackoffTest {

    @Test
    fun `Fixed defaults to DEFAULT_INITIAL_DELAY_MS`() {
        assertEquals(AcceptBackoff.DEFAULT_INITIAL_DELAY_MS, AcceptBackoff.Fixed().delayMs)
    }

    @Test
    fun `Fixed retains explicit delayMs`() {
        assertEquals(42L, AcceptBackoff.Fixed(42L).delayMs)
    }

    @Test
    fun `Exponential defaults to DEFAULT_INITIAL_DELAY_MS and DEFAULT_MAX_DELAY_MS`() {
        val backoff = AcceptBackoff.Exponential()
        assertEquals(AcceptBackoff.DEFAULT_INITIAL_DELAY_MS, backoff.initialMs)
        assertEquals(AcceptBackoff.DEFAULT_MAX_DELAY_MS, backoff.maxMs)
    }

    @Test
    fun `Exponential retains explicit initialMs and maxMs`() {
        val backoff = AcceptBackoff.Exponential(initialMs = 50L, maxMs = 5_000L)
        assertEquals(50L, backoff.initialMs)
        assertEquals(5_000L, backoff.maxMs)
    }

    @Test
    fun `default constants match the documented values`() {
        assertEquals(100L, AcceptBackoff.DEFAULT_INITIAL_DELAY_MS)
        assertEquals(1_000L, AcceptBackoff.DEFAULT_MAX_DELAY_MS)
    }

    @Test
    fun `Fixed and Exponential with the same initial delay are distinct types`() {
        val fixed: AcceptBackoff = AcceptBackoff.Fixed(100L)
        val exponential: AcceptBackoff = AcceptBackoff.Exponential(initialMs = 100L)
        assertNotEquals<AcceptBackoff>(fixed, exponential)
    }
}
