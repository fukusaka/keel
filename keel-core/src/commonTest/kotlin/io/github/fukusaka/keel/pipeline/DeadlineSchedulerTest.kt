package io.github.fukusaka.keel.pipeline

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [DeadlineScheduler]. The clock is a controllable counter, so the
 * tests are fully deterministic and synchronous — no wall-clock waiting, hence no
 * timeout budget is required (pure-logic test per the testing standard).
 */
class DeadlineSchedulerTest {
    private var now = 0L
    private val scheduler = DeadlineScheduler(nowMillis = { now })

    @Test
    fun `a timer fires only at or after its deadline`() {
        var fired = false
        scheduler.schedule(100) { fired = true }
        assertEquals(100, scheduler.nextDeadlineMillis())

        now = 99
        scheduler.expireDue(now)
        assertFalse(fired, "must not fire before the deadline")

        now = 100
        scheduler.expireDue(now)
        assertTrue(fired, "must fire at the deadline")
        assertEquals(Long.MAX_VALUE, scheduler.nextDeadlineMillis(), "fired timer is removed")
    }

    @Test
    fun `touch pushes the deadline back by the original delay`() {
        var fired = false
        val handle = scheduler.schedule(100) { fired = true }

        now = 80
        handle.touch() // deadline now 180
        assertEquals(180, scheduler.nextDeadlineMillis())

        now = 100
        scheduler.expireDue(now)
        assertFalse(fired, "touch must prevent the original deadline from firing")

        now = 180
        scheduler.expireDue(now)
        assertTrue(fired, "must fire at the refreshed deadline")
    }

    @Test
    fun `cancel prevents firing and is idempotent after firing`() {
        var fired = false
        val handle = scheduler.schedule(100) { fired = true }
        handle.cancel()
        assertEquals(Long.MAX_VALUE, scheduler.nextDeadlineMillis())

        now = 200
        scheduler.expireDue(now)
        assertFalse(fired, "cancelled timer must not fire")
        handle.cancel() // no-op, must not throw
    }

    @Test
    fun `same-delay timers fire in FIFO deadline order`() {
        val order = mutableListOf<Int>()
        scheduler.schedule(100) { order.add(1) }
        now = 10
        scheduler.schedule(100) { order.add(2) } // deadline 110
        now = 20
        scheduler.schedule(100) { order.add(3) } // deadline 120

        assertEquals(100, scheduler.nextDeadlineMillis(), "head is the earliest deadline")

        now = 130
        scheduler.expireDue(now)
        assertEquals(listOf(1, 2, 3), order)
    }

    @Test
    fun `touch moves a timer to the tail of its bucket`() {
        val order = mutableListOf<Int>()
        val first = scheduler.schedule(100) { order.add(1) } // deadline 100
        scheduler.schedule(100) { order.add(2) } // deadline 100, after first

        now = 50
        first.touch() // first's deadline now 150 — moves behind #2

        now = 200
        scheduler.expireDue(now)
        assertEquals(listOf(2, 1), order, "touched timer expires last")
    }

    @Test
    fun `distinct delays each track their own earliest deadline`() {
        val fired = mutableListOf<String>()
        scheduler.schedule(50) { fired.add("a") } // deadline 50
        scheduler.schedule(200) { fired.add("b") } // deadline 200
        assertEquals(50, scheduler.nextDeadlineMillis())

        now = 50
        scheduler.expireDue(now)
        assertEquals(listOf("a"), fired)
        assertEquals(200, scheduler.nextDeadlineMillis())

        now = 200
        scheduler.expireDue(now)
        assertEquals(listOf("a", "b"), fired)
    }

    @Test
    fun `a task may reschedule itself`() {
        var count = 0
        lateinit var reschedule: () -> Unit
        reschedule = {
            count++
            if (count < 3) scheduler.schedule(100) { reschedule() }
        }
        scheduler.schedule(100) { reschedule() }

        now = 100
        scheduler.expireDue(now) // count 1, schedules deadline 200
        assertEquals(1, count)
        now = 200
        scheduler.expireDue(now) // count 2, schedules deadline 300
        assertEquals(2, count)
        now = 300
        scheduler.expireDue(now) // count 3, stops
        assertEquals(3, count)
        assertEquals(Long.MAX_VALUE, scheduler.nextDeadlineMillis())
    }

    @Test
    fun `expireDue drains all timers due at the same instant`() {
        val fired = mutableListOf<Int>()
        scheduler.schedule(50) { fired.add(1) }
        scheduler.schedule(100) { fired.add(2) }
        now = 100
        scheduler.expireDue(now)
        assertEquals(setOf(1, 2), fired.toSet())
        assertEquals(Long.MAX_VALUE, scheduler.nextDeadlineMillis())
    }

    @Test
    fun `schedule rejects a non-positive delay`() {
        assertFailsWith<IllegalArgumentException> { scheduler.schedule(0) {} }
        assertFailsWith<IllegalArgumentException> { scheduler.schedule(-1) {} }
    }

    @Test
    fun `no scheduled timers reports no deadline`() {
        assertEquals(Long.MAX_VALUE, scheduler.nextDeadlineMillis())
        scheduler.expireDue(1_000) // must be a no-op, not throw
    }

    @Test
    fun `a throwing timer task neither propagates nor skips the remaining due timers`() {
        // Timers on one scheduler belong to many connections: one
        // connection's throwing deadline task must not kill the EventLoop
        // thread (expireDue is called from the engine wait loop) and must
        // not skip the other connections' due timers in the same sweep.
        var secondFired = false
        scheduler.schedule(50) { error("first task throws") }
        scheduler.schedule(50) { secondFired = true }
        now = 100
        scheduler.expireDue(now) // must not throw
        assertTrue(secondFired, "the second due timer must still fire after the first threw")
        assertEquals(Long.MAX_VALUE, scheduler.nextDeadlineMillis())
    }
}
