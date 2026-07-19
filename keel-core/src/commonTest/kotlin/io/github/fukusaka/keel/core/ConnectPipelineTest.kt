package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests [connectPipeline]: it must connect, run the initializer on the
 * channel's EventLoop thread, return the configured [PipelinedChannel], and
 * close the channel if the initializer throws (no leak).
 */
class ConnectPipelineTest {

    private object Probe : InboundHandler

    /** A pipelined channel that records whether it was closed. */
    private class RecordingChannel :
        AbstractPipelinedChannel(TestIoTransport(), PrintLogger("connect-pipeline-test")) {
        var closed = false
            private set

        override fun close() {
            closed = true
            super.close()
        }
    }

    /** Minimal [StreamEngine] whose [connect] returns [channel]. */
    private class FakeStreamEngine(private val channel: RecordingChannel) : StreamEngine {
        override val config: IoEngineConfig = IoEngineConfig()
        override val coroutineContext: CoroutineContext = Dispatchers.Unconfined
        override suspend fun close() {}
        override suspend fun bind(address: SocketAddress, bindConfig: BindConfig): StreamServer =
            throw NotImplementedError("bind is not needed for connectPipeline tests")
        override suspend fun connect(address: SocketAddress): Channel = channel
    }

    @Test
    fun `connectPipeline runs the initializer and returns the configured channel`() = runTest {
        val channel = RecordingChannel()
        val engine = FakeStreamEngine(channel)

        val result = engine.connectPipeline("127.0.0.1", 8080) {
            it.pipeline.addLast("probe", Probe)
        }

        assertSame(channel, result)
        assertNotNull(result.pipeline.get("probe"), "the initializer configured the returned channel")
        assertTrue(!channel.closed, "a successful connectPipeline does not close the channel")
    }

    @Test
    fun `connectPipeline closes the channel when the initializer throws`() = runTest {
        val channel = RecordingChannel()
        val engine = FakeStreamEngine(channel)
        val boom = IllegalStateException("bad setup")

        val thrown = assertFailsWith<IllegalStateException> {
            engine.connectPipeline("127.0.0.1", 8080) { throw boom }
        }

        assertSame(boom, thrown, "the initializer's exception propagates")
        assertTrue(channel.closed, "a failed initializer must not leak the connection")
    }
}
