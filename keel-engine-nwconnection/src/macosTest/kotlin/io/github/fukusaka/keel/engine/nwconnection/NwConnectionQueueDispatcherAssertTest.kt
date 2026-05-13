package io.github.fukusaka.keel.engine.nwconnection

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Pin the I/O ownership invariant contract on
 * [NwConnectionQueueDispatcher.assertInConnectionQueue]:
 *
 * - On the dispatcher's own serial queue the check passes silently.
 * - From any other dispatch queue (or the test thread itself) the
 *   check fails fast with an `IllegalStateException` carrying the
 *   caller-provided operation name.
 *
 * This is the upstream-delegated equivalent of `assertInEventLoop`
 * on the POSIX engines.
 */
@OptIn(ExperimentalForeignApi::class)
class NwConnectionQueueDispatcherAssertTest {

    @Test
    fun `assertInConnectionQueue passes when running on the wrapped queue`() = runBlocking {
        val queue = dispatch_queue_create("io.github.fukusaka.keel.test.assert-conn-queue", null)
            ?: error("dispatch_queue_create returned null")
        val dispatcher = NwConnectionQueueDispatcher(queue)
        val done = CompletableDeferred<Result<Unit>>()
        dispatch_async(queue) {
            done.complete(runCatching { dispatcher.assertInConnectionQueue("test.onQueue") })
        }
        val result = done.await()
        assertTrue(result.isSuccess, "expected success on the wrapped queue, got ${result.exceptionOrNull()}")
    }

    @Test
    fun `assertInConnectionQueue fails when called from a different queue`() = runBlocking {
        val ownedQueue = dispatch_queue_create("io.github.fukusaka.keel.test.assert-owned", null)
            ?: error("dispatch_queue_create owned returned null")
        val otherQueue = dispatch_queue_create("io.github.fukusaka.keel.test.assert-other", null)
            ?: error("dispatch_queue_create other returned null")
        val dispatcher = NwConnectionQueueDispatcher(ownedQueue)
        val done = CompletableDeferred<Result<Unit>>()
        dispatch_async(otherQueue) {
            done.complete(runCatching { dispatcher.assertInConnectionQueue("test.offQueue") })
        }
        val ex = assertFailsWith<IllegalStateException> {
            done.await().getOrThrow()
        }
        assertTrue(
            ex.message?.contains("test.offQueue") == true,
            "exception message should carry the operation name, was: ${ex.message}",
        )
    }

    @Test
    fun `assertInConnectionQueue fails when called from the test thread directly`() {
        val queue = dispatch_queue_create("io.github.fukusaka.keel.test.assert-direct", null)
            ?: error("dispatch_queue_create direct returned null")
        val dispatcher = NwConnectionQueueDispatcher(queue)
        val ex = assertFailsWith<IllegalStateException> {
            dispatcher.assertInConnectionQueue("test.directThread")
        }
        assertTrue(
            ex.message?.contains("test.directThread") == true,
            "exception message should carry the operation name, was: ${ex.message}",
        )
    }
}
