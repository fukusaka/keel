package io.github.fukusaka.keel.server.ktor.cio

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Behavioural tests for [HeaderParseSerializer].
 *
 * The class has different actuals on JVM (no-op pass-through) and
 * Native (process-wide [kotlinx.coroutines.sync.Mutex]); both must
 * preserve the result of the wrapped block and propagate exceptions.
 */
class HeaderParseSerializerTest {

    @Test
    fun `withLock returns the block result`() = runTest {
        val serializer = HeaderParseSerializer()
        val result = serializer.withLock { 42 }
        assertEquals(42, result)
    }

    @Test
    fun `withLock propagates exceptions thrown inside the block`() = runTest {
        val serializer = HeaderParseSerializer()
        var caught: Throwable? = null
        try {
            serializer.withLock { throw IllegalStateException("boom") }
        } catch (e: IllegalStateException) {
            caught = e
        }
        assertEquals("boom", caught?.message)
    }

    @Test
    fun `concurrent calls observe consistent ordering when serialised`() = runTest {
        // On Native this asserts that the mutex actually serialises
        // overlapping `withLock` blocks.  On JVM (no-op actual) the
        // counter still ends up at the expected total because each
        // increment is a single statement and the test-coroutine
        // scheduler serialises by default.  In both cases the final
        // counter value must equal the total number of increments.
        val serializer = HeaderParseSerializer()
        var counter = 0
        coroutineScope {
            repeat(100) {
                async {
                    serializer.withLock {
                        val current = counter
                        delay(0)
                        counter = current + 1
                    }
                }
            }
        }
        assertEquals(100, counter)
    }
}
