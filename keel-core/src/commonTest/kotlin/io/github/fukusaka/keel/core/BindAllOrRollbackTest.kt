package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

/**
 * Contract tests for [bindAllOrRollback]: bind order, all-or-nothing
 * rollback in reverse order, suppressed-exception bookkeeping, and the
 * empty-list guard. Purely synchronous — no timeout needed.
 */
class BindAllOrRollbackTest {

    private val logger = NoopLoggerFactory.logger("test")

    private fun spec(port: Int) = BindSpec(InetSocketAddress("127.0.0.1", port))

    @Test
    fun `binds every entry in order and returns listeners in bind order`() {
        val events = mutableListOf<String>()
        val result = bindAllOrRollback(
            binds = listOf(spec(1), spec(2), spec(3)),
            logger = logger,
            closeOne = { events.add("close:$it") },
        ) { spec ->
            val port = (spec.address as InetSocketAddress).port
            events.add("bind:$port")
            port
        }
        assertContentEquals(listOf(1, 2, 3), result)
        assertContentEquals(listOf("bind:1", "bind:2", "bind:3"), events)
    }

    @Test
    fun `rolls back already-bound listeners in reverse order when a bind fails`() {
        val events = mutableListOf<String>()
        val failure = assertFailsWith<IllegalStateException> {
            bindAllOrRollback(
                binds = listOf(spec(1), spec(2), spec(3)),
                logger = logger,
                closeOne = { events.add("close:$it") },
            ) { spec ->
                val port = (spec.address as InetSocketAddress).port
                if (port == 3) throw IllegalStateException("bind failed for $port")
                events.add("bind:$port")
                port
            }
        }
        assertEquals("bind failed for 3", failure.message)
        assertContentEquals(listOf("bind:1", "bind:2", "close:2", "close:1"), events)
    }

    @Test
    fun `attaches rollback close failures as suppressed and keeps rolling back`() {
        val events = mutableListOf<String>()
        val closeFailure = IllegalStateException("close failed for 2")
        val failure = assertFailsWith<IllegalStateException> {
            bindAllOrRollback(
                binds = listOf(spec(1), spec(2), spec(3)),
                logger = logger,
                closeOne = { port: Int ->
                    if (port == 2) throw closeFailure
                    events.add("close:$port")
                },
            ) { spec ->
                val port = (spec.address as InetSocketAddress).port
                if (port == 3) throw IllegalStateException("bind failed for 3")
                port
            }
        }
        assertEquals("bind failed for 3", failure.message)
        // The close failure for listener 2 did not stop listener 1's rollback.
        assertContentEquals(listOf("close:1"), events)
        assertContentEquals(listOf<Throwable>(closeFailure), failure.suppressedExceptions)
        assertSame(closeFailure, failure.suppressedExceptions.single())
    }

    @Test
    fun `rejects an empty bind list`() {
        assertFailsWith<IllegalArgumentException> {
            bindAllOrRollback(
                binds = emptyList(),
                logger = logger,
                closeOne = { _: Int -> },
            ) { 0 }
        }
    }
}
