package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [AbstractPipelinedChannel]'s peer-FIN close lifecycle.
 *
 * On peer-FIN the engine fires `transport.onReadClosed`; the channel
 * surfaces inactivation through the pipeline and then auto-closes the fd
 * **only in Pipeline mode** — a pipeline with user handlers and no
 * [SuspendBridgeHandler], where keel owns the connection lifecycle. A
 * Coroutine-mode channel (a bridge is wired, or the pipeline is empty
 * before the lazy bridge) is the caller's resource and is left open for
 * the caller to close. These tests pin that contract.
 *
 * Engine integration tests (e.g. `KqueueEngineReadWriteTest.readReturnsMinusOneOnEof`)
 * exercise the same path through real sockets; this file covers the
 * behaviour at the abstraction level so all engines benefit from the
 * single core change without per-engine assertions.
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

    // --- Peer-FIN close: Pipeline mode only ---

    @Test
    fun `peer-FIN on a pipeline-mode channel closes it`() {
        val (transport, channel) = makeChannel()
        // Pipeline mode: a user handler is installed and no lazy
        // SuspendBridgeHandler. keel owns the connection lifecycle (no
        // Channel handle is exposed to a caller), so the fd is closed here
        // — deferring would leak it in CLOSE-WAIT.
        channel.pipeline.addLast(
            "consumer",
            object : InboundHandler {
                override fun onInactive(ctx: PipelineHandlerContext) = ctx.propagateInactive()
            },
        )

        transport.onReadClosed?.invoke()

        assertFalse(channel.isOpen, "a pipeline-mode channel must close on peer-FIN")
        assertEquals(1, transport.closeCount, "close() must run on peer-FIN")
    }

    @Test
    fun `peer-FIN on a coroutine-mode channel before any read does not auto-close it`() {
        val (transport, channel) = makeChannel()

        // Empty pipeline: a Coroutine-mode channel before its lazy bridge.
        transport.onReadClosed?.invoke()

        // The channel is the caller's resource — left open for them to close.
        assertTrue(channel.isOpen, "a coroutine-mode channel is not auto-closed")
        assertEquals(0, transport.closeCount, "close() must not run on peer-FIN")
    }

    @Test
    fun `peer-FIN with a bridge installed does not auto-close the channel`() {
        val (transport, channel) = makeChannel()
        val bridge = channel.ensureBridge()
        assertTrue(channel.isOpen)
        assertFalse(bridge.isEof)

        transport.onReadClosed?.invoke()

        // The bridge observes EOF so `read()` returns -1, but the channel
        // stays open — the caller closes their Channel.
        assertTrue(bridge.isEof, "bridge observes EOF")
        assertTrue(channel.isOpen, "a coroutine-mode channel is not auto-closed")
        assertEquals(0, transport.closeCount)
    }

    @Test
    fun `ensureBridge after peer-FIN replays EOF and leaves the channel open`() {
        val (transport, channel) = makeChannel()
        // Peer-FIN before the bridge is installed.
        transport.onReadClosed?.invoke()

        val bridge = channel.ensureBridge()

        // DefaultPipeline replays onInactive to the late-installed bridge,
        // so a pending read resolves with -1; the channel is not closed.
        assertTrue(bridge.isEof, "bridge observes EOF via replay")
        assertTrue(channel.isOpen, "the channel is left open for the caller")
        assertEquals(0, transport.closeCount)
    }

    @Test
    fun `a coroutine-mode channel peer-FIN then a local close closes it once`() {
        val (transport, channel) = makeChannel()
        transport.onReadClosed?.invoke()
        assertEquals(0, transport.closeCount, "peer-FIN did not auto-close")

        // The caller closes their Channel.
        channel.close()
        assertFalse(channel.isOpen)
        assertEquals(1, transport.closeCount)
    }

    @Test
    fun `local close without prior peer-FIN does not propagate notifyInactive`() {
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
        // it does not synthesise a [pipeline.notifyInactive]. Pipeline-level
        // inactivation only fires through the engine peer-close path.
        assertEquals(0, handler.inactiveCount)
        assertFalse(channel.isOpen)
        assertEquals(1, transport.closeCount)
    }
}
