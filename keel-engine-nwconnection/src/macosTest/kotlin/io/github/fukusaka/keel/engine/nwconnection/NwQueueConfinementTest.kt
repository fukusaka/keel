package io.github.fukusaka.keel.engine.nwconnection

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Pins [NwQueueConfinement]: it reports "owner" exactly when the caller runs on
 * the tagged NWConnection serial dispatch queue, and "not owner" off it — the
 * predicate a pooled allocator routes releases against.
 *
 * An on-queue release takes the freelist fast path even across GCD worker-pthread
 * migration (the case a thread-id token would misclassify); a genuinely off-queue
 * release — e.g. a pull-mode `asSource` refill on the caller's coroutine thread —
 * reports `false` and is funnelled to the owner's return queue instead of racing
 * the queue's freelist. The allocator-side routing that consumes this predicate is
 * pinned in keel-io's `CrossThreadReturnQueueTest` (always-owner → freelist,
 * off-context → queue).
 */
@OptIn(ExperimentalForeignApi::class)
class NwQueueConfinementTest {

    // Hang-detection budget; one dispatch_async onto a fresh GCD serial queue,
    // awaited via CompletableDeferred. Mirrors NwConnectionQueueDispatcherRoutingTest.
    private val asyncBudget = 5.seconds

    @Test
    fun `reports not-owner off the tagged queue`() {
        val queue = dispatch_queue_create("io.github.fukusaka.keel.test.confinement-off", null)
            ?: error("dispatch_queue_create returned null")
        val token = NwQueueConfinement(queue)
        // The test thread is not [queue]; dispatch_get_specific returns null there.
        assertFalse(token.isCurrentContextOwner(), "off the tagged queue the token must not report owner")
    }

    @Test
    fun `reports owner on the tagged queue`() = runBlocking {
        withTimeout(asyncBudget) {
            val queue = dispatch_queue_create("io.github.fukusaka.keel.test.confinement-on", null)
                ?: error("dispatch_queue_create returned null")
            val token = NwQueueConfinement(queue)
            val onQueue = CompletableDeferred<Boolean>()
            // Run the check on [queue]: dispatch_get_specific(marker) returns marker
            // there, so the token reports owner.
            dispatch_async(queue) {
                onQueue.complete(token.isCurrentContextOwner())
            }
            assertTrue(onQueue.await(), "on the tagged queue the token must report owner")
        }
    }
}
