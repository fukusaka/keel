package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SuspendMessageBridgeTest {

    // Simple typed message for testing.
    private data class TestMessage(val value: String)

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("bridge-test")) {}

    private fun createPipeline(bridge: SuspendMessageBridge<TestMessage>): Pipeline {
        val pipeline = channel.pipeline
        pipeline.addLast("bridge", bridge)
        return pipeline
    }

    @Test
    fun `receive delivers typed message from pipeline`() {
        runTest {
            val bridge = SuspendMessageBridge(TestMessage::class)
            val pipeline = createPipeline(bridge)

            pipeline.notifyRead(TestMessage("hello"))

            val result = bridge.receiveCatching()
            assertTrue(result.isSuccess)
            assertEquals("hello", result.getOrThrow().value)
        }
    }

    @Test
    fun `multiple messages are delivered in order`() {
        runTest {
            val bridge = SuspendMessageBridge(TestMessage::class)
            val pipeline = createPipeline(bridge)

            pipeline.notifyRead(TestMessage("first"))
            pipeline.notifyRead(TestMessage("second"))
            pipeline.notifyRead(TestMessage("third"))

            assertEquals("first", bridge.receiveCatching().getOrThrow().value)
            assertEquals("second", bridge.receiveCatching().getOrThrow().value)
            assertEquals("third", bridge.receiveCatching().getOrThrow().value)
        }
    }

    @Test
    fun `onInactive closes channel cleanly`() {
        runTest {
            val bridge = SuspendMessageBridge(TestMessage::class)
            val pipeline = createPipeline(bridge)

            pipeline.notifyInactive()

            val result = bridge.receiveCatching()
            assertTrue(result.isClosed)
            // Clean close — no exception.
            val cause = result.exceptionOrNull()
            assertTrue(cause == null)
        }
    }

    @Test
    fun `onError closes channel with cause`() {
        runTest {
            val bridge = SuspendMessageBridge(TestMessage::class)
            val pipeline = createPipeline(bridge)

            val error = InjectedFault("parse failed")
            pipeline.notifyError(error)

            val result = bridge.receiveCatching()
            assertTrue(result.isClosed)
            assertNotNull(result.exceptionOrNull())
            assertEquals("parse failed", result.exceptionOrNull()!!.message)
        }
    }

    @Test
    fun `closeAndReleaseBuffered releases only the undelivered messages`() {
        runTest {
            val released = mutableListOf<String>()
            val bridge = SuspendMessageBridge(TestMessage::class, releaseUndelivered = { released.add(it.value) })
            val pipeline = createPipeline(bridge)

            pipeline.notifyRead(TestMessage("a"))
            pipeline.notifyRead(TestMessage("b"))
            // The consumer takes one; the other is left buffered-and-undelivered.
            assertEquals("a", bridge.receiveCatching().getOrThrow().value)

            bridge.closeAndReleaseBuffered()

            // Only the message the consumer never received is released.
            assertEquals(listOf("b"), released)
            assertTrue(bridge.receiveCatching().isClosed)
        }
    }

    @Test
    fun `onInactive releases every buffered undelivered message`() {
        runTest {
            val released = mutableListOf<String>()
            val bridge = SuspendMessageBridge(TestMessage::class, releaseUndelivered = { released.add(it.value) })
            val pipeline = createPipeline(bridge)

            pipeline.notifyRead(TestMessage("x"))
            pipeline.notifyRead(TestMessage("y"))
            // Peer-FIN path: onInactive must drain + release the buffered frames.
            pipeline.notifyInactive()

            assertEquals(listOf("x", "y"), released)
        }
    }

    @Test
    fun `a message buffered after the consumer gave up is reclaimed on close`() {
        runTest {
            val released = mutableListOf<String>()
            val bridge = SuspendMessageBridge(TestMessage::class, releaseUndelivered = { released.add(it.value) })
            val pipeline = createPipeline(bridge)

            // The consumer suspends waiting on the empty channel, then is
            // cancelled (e.g. a request timeout) before any message arrives —
            // this removes it as the waiting receiver.
            val consumer = launch(start = CoroutineStart.UNDISPATCHED) {
                bridge.receiveCatching()
            }
            consumer.cancel()

            // A message the EventLoop decoded after the consumer gave up is then
            // buffered (no live receiver). Connection teardown must reclaim its
            // pooled payload rather than leak it — the reachable cancellation
            // path (the atomic dequeue-then-cancel window is additionally
            // covered by the channel's onUndeliveredElement hook).
            pipeline.notifyRead(TestMessage("stranded"))
            pipeline.notifyInactive()

            assertEquals(listOf("stranded"), released)
        }
    }

    @Test
    fun `a message arriving after close is released not propagated`() {
        runTest {
            val released = mutableListOf<String>()
            val bridge = SuspendMessageBridge(TestMessage::class, releaseUndelivered = { released.add(it.value) })
            val pipeline = createPipeline(bridge)

            bridge.closeAndReleaseBuffered()
            // A late frame (e.g. the decoder delivering after the consumer
            // stopped) hits the closed channel: trySend fails and the release
            // hook reclaims it instead of leaking it downstream.
            pipeline.notifyRead(TestMessage("late"))

            assertEquals(listOf("late"), released)
        }
    }

    @Test
    fun `closeAndReleaseBuffered without a release hook keeps buffered messages receivable`() {
        runTest {
            // Default (no releaseUndelivered, e.g. the HTTP bridge): buffered
            // messages are not drained — the consumer can still receive them.
            val bridge = SuspendMessageBridge(TestMessage::class)
            val pipeline = createPipeline(bridge)

            pipeline.notifyRead(TestMessage("kept"))
            bridge.closeAndReleaseBuffered()

            val result = bridge.receiveCatching()
            assertTrue(result.isSuccess)
            assertEquals("kept", result.getOrThrow().value)
        }
    }

    @Test
    fun `suspendMessageBridge factory builds a working bridge for the reified type`() {
        runTest {
            // The reified factory drops the explicit KClass argument.
            val bridge = suspendMessageBridge<TestMessage>()
            val pipeline = createPipeline(bridge)

            pipeline.notifyRead(TestMessage("reified"))

            val result = bridge.receiveCatching()
            assertTrue(result.isSuccess)
            assertEquals("reified", result.getOrThrow().value)
        }
    }

    @Test
    fun `suspendMessageBridge factory forwards the release hook`() {
        runTest {
            val released = mutableListOf<String>()
            val bridge = suspendMessageBridge<TestMessage>(releaseUndelivered = { released.add(it.value) })
            val pipeline = createPipeline(bridge)

            pipeline.notifyRead(TestMessage("m"))
            pipeline.notifyInactive() // undelivered → release hook fires

            assertEquals(listOf("m"), released)
        }
    }

    @Test
    fun `non-matching messages are propagated downstream`() {
        val bridge = SuspendMessageBridge(TestMessage::class)
        val pipeline = createPipeline(bridge)

        // Feed a String (not TestMessage) — should propagate to TAIL.
        // TailHandler logs a warning but doesn't crash.
        pipeline.notifyRead("not a TestMessage")

        // Bridge channel should be empty (no matching message queued).
        runTest {
            // Send a real message so we can verify the bridge still works.
            pipeline.notifyRead(TestMessage("after-mismatch"))
            val result = bridge.receiveCatching()
            assertTrue(result.isSuccess)
            assertEquals("after-mismatch", result.getOrThrow().value)
        }
    }
}
