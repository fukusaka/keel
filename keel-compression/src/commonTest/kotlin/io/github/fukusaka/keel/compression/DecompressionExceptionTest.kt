package io.github.fukusaka.keel.compression

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Exception hierarchy contract.
 *
 * The SPI documents [DecompressionLimitException] as a subclass of
 * [DecompressionException] so callers may `catch (e: DecompressionException)`
 * to catch both malformed-input and limit-exceeded failures with one
 * handler. This is a public contract that backends rely on — pin it here
 * so a future refactor cannot silently break it.
 */
class DecompressionExceptionTest {

    @Test
    fun `DecompressionLimitException is a subclass of DecompressionException`() {
        // The subclass relation is pinned at compile time by the `val e:
        // DecompressionException = DecompressionLimitException(...)`
        // assignment — if `DecompressionLimitException` ever stops extending
        // `DecompressionException`, this file no longer compiles. The runtime
        // `assertTrue(e is ...)` is therefore a tautology (the construction
        // type and the runtime type are the same `DecompressionLimitException`
        // instance), kept only as documentation of the intended hierarchy.
        val e: DecompressionException = DecompressionLimitException("limit hit")
        assertTrue(e is DecompressionLimitException)
    }

    @Test
    fun `DecompressionException carries message`() {
        val e = DecompressionException("malformed header")
        assertEquals("malformed header", e.message)
    }

    @Test
    fun `DecompressionException preserves cause`() {
        val root = RuntimeException("root")
        val e = DecompressionException("wrapper", root)
        assertSame(root, e.cause)
    }

    @Test
    fun `DecompressionException with no cause has null cause`() {
        val e = DecompressionException("standalone")
        assertNull(e.cause)
    }

    @Test
    fun `DecompressionLimitException carries message`() {
        val e = DecompressionLimitException("max-output-size exceeded")
        assertEquals("max-output-size exceeded", e.message)
    }
}
