package io.github.fukusaka.keel.apple

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_async
import platform.darwin.dispatch_group_create
import platform.darwin.dispatch_group_enter
import platform.darwin.dispatch_group_leave
import platform.darwin.dispatch_group_wait
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Behavioural tests for [DispatchQueueLocal] — the Apple-platform
 * per-`dispatch_queue_t` storage primitive used by the per-connection-queue `HttpHeadersPool` fix.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class DispatchQueueLocalTest {

    private val budget = 10.seconds

    private class Box(val id: Int)

    @Test
    fun `current returns fallback when no scoped value installed`() {
        var fallbackCalls = 0
        val fallbackBox = Box(-1)
        val local = DispatchQueueLocal<Box> {
            fallbackCalls++
            fallbackBox
        }
        // Called from the test thread — not on any tagged queue.
        assertFalse(local.isScopedHere())
        assertSame(fallbackBox, local.current())
        assertTrue(fallbackCalls >= 1, "fallback should have been invoked")
    }

    @Test
    fun `install binds a value visible from a block on that queue`() = runBlocking {
        withTimeout(budget) {
            val queue = dispatch_queue_create("keel.test.dql.install", null)
                ?: error("dispatch_queue_create returned null")
            val scoped = Box(42)
            val local = DispatchQueueLocal<Box> { Box(-1) }
            local.install(queue, scoped)

            val seen = CompletableDeferred<Box>()
            val scopedFlag = CompletableDeferred<Boolean>()
            dispatch_async(queue) {
                scopedFlag.complete(local.isScopedHere())
                seen.complete(local.current())
            }
            assertTrue(scopedFlag.await(), "isScopedHere should be true inside the queue block")
            assertSame(scoped, seen.await(), "current() inside the queue must return the installed value")
        }
    }

    @Test
    fun `two queues each see their own installed value`() = runBlocking {
        withTimeout(budget) {
            val q1 = dispatch_queue_create("keel.test.dql.q1", null) ?: error("q1 null")
            val q2 = dispatch_queue_create("keel.test.dql.q2", null) ?: error("q2 null")
            val box1 = Box(1)
            val box2 = Box(2)
            val local = DispatchQueueLocal<Box> { Box(-1) }
            local.install(q1, box1)
            local.install(q2, box2)

            val seen1 = CompletableDeferred<Box>()
            val seen2 = CompletableDeferred<Box>()
            dispatch_async(q1) { seen1.complete(local.current()) }
            dispatch_async(q2) { seen2.complete(local.current()) }

            assertSame(box1, seen1.await())
            assertSame(box2, seen2.await())
        }
    }

    @Test
    fun `independent DispatchQueueLocal instances do not collide on the same queue`() = runBlocking {
        withTimeout(budget) {
            val queue = dispatch_queue_create("keel.test.dql.independent", null)
                ?: error("queue null")
            val localA = DispatchQueueLocal<Box> { Box(-10) }
            val localB = DispatchQueueLocal<Box> { Box(-20) }
            val boxA = Box(100)
            val boxB = Box(200)
            localA.install(queue, boxA)
            localB.install(queue, boxB)

            val seenA = CompletableDeferred<Box>()
            val seenB = CompletableDeferred<Box>()
            dispatch_async(queue) {
                seenA.complete(localA.current())
                seenB.complete(localB.current())
            }
            // Distinct keys → each instance resolves its own value, no cross-talk.
            assertSame(boxA, seenA.await())
            assertSame(boxB, seenB.await())
        }
    }

    @Test
    fun `repeated borrow-release on a scoped queue stays consistent under migration`() = runBlocking {
        withTimeout(budget) {
            // Mirrors the HttpHeadersPool usage: a per-queue mutable
            // container is mutated across many blocks; GCD may migrate
            // the queue across worker pthreads but every block must see
            // the same instance. We push/pop a counter and assert the
            // container is never lost or swapped.
            val queue = dispatch_queue_create("keel.test.dql.churn", null)
                ?: error("queue null")
            val local = DispatchQueueLocal<ArrayDeque<Int>> { ArrayDeque() }
            local.install(queue, ArrayDeque())

            val mismatches = AtomicInt(0)
            val iterations = 50_000
            val group = dispatch_group_create()
            repeat(iterations) { i ->
                dispatch_group_enter(group)
                dispatch_async(queue) {
                    val stack = local.current()
                    stack.addLast(i)
                    val popped = stack.removeLast()
                    if (popped != i) mismatches.fetchAndAdd(1)
                    dispatch_group_leave(group)
                }
            }
            dispatch_group_wait(group, DISPATCH_TIME_FOREVER)

            assertEquals(
                expected = 0,
                actual = mismatches.load(),
                message = "per-queue container was lost or swapped under GCD migration",
            )
        }
    }
}
