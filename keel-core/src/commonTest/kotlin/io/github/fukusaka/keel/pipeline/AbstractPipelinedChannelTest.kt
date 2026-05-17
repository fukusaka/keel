package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [AbstractPipelinedChannel]'s deferred close-on-peer-FIN
 * lifecycle.
 *
 * The engine-driven peer-close path (`transport.onReadClosed`) auto-closes
 * the channel after surfacing inactivation through the pipeline. When the
 * lazy [SuspendBridgeHandler] is not yet installed, the close is deferred
 * to [AbstractPipelinedChannel.ensureBridge] so a pending suspend reader
 * has a chance to observe `-1` from the bridge before the channel becomes
 * `isOpen = false`. These tests pin that contract.
 *
 * Engine integration tests (e.g. `KqueueEngineReadWriteTest.readReturnsMinusOneOnEof`)
 * exercise the same path through real sockets; this file covers the
 * behaviour at the abstraction level so all engines benefit from the
 * single core fix without per-engine assertions.
 */
internal class AbstractPipelinedChannelTest {

    private val logger = PrintLogger("test")

    private class CountingTransport : TestIoTransport() {
        var closeCount: Int = 0

        override fun close() {
            closeCount++
            super.close()
        }
    }

    private fun makeChannel(transport: CountingTransport = CountingTransport()) =
        Pair(transport, object : AbstractPipelinedChannel(transport, logger) {})

    // --- Peer-FIN deferred close ---

    @Test
    fun `peer-FIN before bridge is installed leaves channel open until ensureBridge`() {
        val (transport, channel) = makeChannel()

        // Simulate engine-driven peer-FIN dispatch.
        transport.onReadClosed?.invoke()

        // Channel stays open: the deferred close waits for the bridge to be
        // wired so a pending suspend reader can observe EOF.
        assertTrue(channel.isOpen, "channel must stay open until bridge is installed")
        assertEquals(0, transport.closeCount, "close() must not run yet")
    }

    @Test
    fun `ensureBridge runs the deferred close after replaying onInactive`() {
        val (transport, channel) = makeChannel()
        transport.onReadClosed?.invoke()
        assertTrue(channel.isOpen)

        // First read in the user code installs the bridge.
        val bridge = channel.ensureBridge()

        // The replayed [PipelineHandler.onInactive] fired by [DefaultPipeline]
        // sets bridge.eof, then the deferred close runs.
        assertTrue(bridge.isEof, "bridge must observe EOF via replay")
        assertFalse(channel.isOpen, "channel closes after bridge replay")
        assertEquals(1, transport.closeCount, "close() runs exactly once")
    }

    @Test
    fun `peer-FIN on a pipeline-mode channel closes immediately without waiting for a bridge`() {
        val (transport, channel) = makeChannel()
        // Pipeline mode: a user handler is installed and no lazy
        // SuspendBridgeHandler will ever be created (ensureBridge is never
        // called). The handler is the connection's consumer.
        channel.pipeline.addLast(
            "consumer",
            object : InboundHandler {
                override fun onInactive(ctx: PipelineHandlerContext) = ctx.propagateInactive()
            },
        )

        transport.onReadClosed?.invoke()

        // The handler received onInactive; there is no bridge to wait for,
        // so the fd must be closed now. Deferring would leak it in
        // CLOSE_WAIT forever, since ensureBridge is never called.
        assertFalse(channel.isOpen, "a pipeline-mode channel must close on peer-FIN")
        assertEquals(1, transport.closeCount, "close() must run on peer-FIN")
    }

    @Test
    fun `peer-FIN after bridge is installed closes the channel synchronously`() {
        val (transport, channel) = makeChannel()
        // Bridge installed before peer-FIN (the standard active-reader case).
        val bridge = channel.ensureBridge()
        assertTrue(channel.isOpen)
        assertFalse(bridge.isEof)

        transport.onReadClosed?.invoke()

        // Bridge is wired, so [transport.onReadClosed] closes immediately.
        assertTrue(bridge.isEof)
        assertFalse(channel.isOpen)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `multiple ensureBridge calls do not double-close after deferred peer-FIN`() {
        val (transport, channel) = makeChannel()
        transport.onReadClosed?.invoke()

        channel.ensureBridge()
        channel.ensureBridge()
        channel.ensureBridge()

        assertEquals(1, transport.closeCount, "deferred close must not re-run")
    }

    @Test
    fun `local close without prior peer-FIN does not propagate notifyInactive twice`() {
        val (transport, channel) = makeChannel()
        val handler = object : InboundHandler {
            var inactiveCount = 0
            override fun onInactive(ctx: PipelineHandlerContext) {
                inactiveCount++
                ctx.propagateInactive()
            }
        }
        channel.pipeline.addLast("counter", handler)

        // User-initiated close.
        channel.close()

        // [AbstractPipelinedChannel.close] only delegates to transport.close();
        // it does not synthesise a [pipeline.notifyInactive]. The contract is
        // preserved: pipeline-level inactivation only fires through the engine
        // peer-close path.
        assertEquals(0, handler.inactiveCount)
        assertFalse(channel.isOpen)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `peer-FIN then local close runs close exactly once via deferred path`() {
        val (transport, channel) = makeChannel()
        transport.onReadClosed?.invoke()
        // Channel stays open with pendingClose latched.

        // User-initiated close arrives before any read — close runs now.
        channel.close()
        assertFalse(channel.isOpen)
        assertEquals(1, transport.closeCount)

        // Subsequent ensureBridge must not re-trigger the deferred close.
        channel.ensureBridge()
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `bridge installed via ensureBridge before peer-FIN — pending close path is unused`() {
        val (transport, channel) = makeChannel()
        channel.ensureBridge() // bridge != null
        assertEquals(0, transport.closeCount)

        transport.onReadClosed?.invoke()

        // Synchronous close path: pendingClose is never set.
        assertFalse(channel.isOpen)
        assertEquals(1, transport.closeCount)
    }
}
