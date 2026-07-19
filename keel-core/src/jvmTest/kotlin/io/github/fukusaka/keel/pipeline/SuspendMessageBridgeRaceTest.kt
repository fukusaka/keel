package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Concurrency regression for the pooled-payload release contract of
 * [SuspendMessageBridge] under the receive/cancel race.
 *
 * A request timeout can cancel the consumer at the exact moment the EventLoop
 * hands it the decoded message: the message is dequeued from the channel but the
 * receive is cancelled before it returns (the atomic dequeue-then-cancel window).
 * Without the channel's `onUndeliveredElement` hook that payload is silently
 * lost; with it the release hook reclaims it.
 *
 * This races delivery against cancellation on real threads many times and
 * asserts conservation — every sent message is either received or released,
 * never lost. Post-fix this is deterministically zero-loss (no false-fail
 * timing threshold), so it is a stable always-run guard; if the
 * `onUndeliveredElement` wiring is removed it fails reliably (empirically the
 * race hits the window in a large fraction of iterations).
 */
class SuspendMessageBridgeRaceTest {

    private data class RaceMessage(val value: String)

    @Test
    fun `no pooled payload is lost when receive races cancellation`() {
        val received = AtomicInteger(0)
        val released = AtomicInteger(0)
        val iterations = 4000

        runBlocking {
            withTimeout(30.seconds) {
                repeat(iterations) {
                    val channel = object : AbstractPipelinedChannel(TestIoTransport(), PrintLogger("race")) {}
                    val bridge = SuspendMessageBridge(
                        RaceMessage::class,
                        releaseUndelivered = { released.incrementAndGet() },
                    )
                    channel.pipeline.addLast("bridge", bridge)

                    val consumer = launch(Dispatchers.Default) {
                        try {
                            if (bridge.receiveCatching().isSuccess) received.incrementAndGet()
                        } catch (_: Throwable) {
                            // Cancelled before the value was returned to us.
                        }
                    }
                    // Race the delivery against the cancellation on separate threads.
                    val deliver = launch(Dispatchers.Default) { channel.pipeline.notifyRead(RaceMessage("m")) }
                    val cancel = launch(Dispatchers.Default) { consumer.cancel() }
                    deliver.join()
                    cancel.join()
                    consumer.join()
                    // Teardown reclaims anything still buffered (never delivered).
                    bridge.closeAndReleaseBuffered()
                }
            }
        }

        assertEquals(
            iterations,
            received.get() + released.get(),
            "a pooled payload was lost in the receive/cancel race (received=${received.get()}, released=${released.get()})",
        )
    }
}
