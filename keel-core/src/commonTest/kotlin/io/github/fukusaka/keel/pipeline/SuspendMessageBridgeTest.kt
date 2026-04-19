package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.PrintLogger
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

            val error = RuntimeException("parse failed")
            pipeline.notifyError(error)

            val result = bridge.receiveCatching()
            assertTrue(result.isClosed)
            assertNotNull(result.exceptionOrNull())
            assertEquals("parse failed", result.exceptionOrNull()!!.message)
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
