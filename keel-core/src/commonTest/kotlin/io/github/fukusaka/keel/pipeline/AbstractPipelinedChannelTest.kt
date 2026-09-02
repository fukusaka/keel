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

    /**
     * Records what was already wired the **first** time the attach hook ran.
     *
     * First, not last: a hook called once too early and again in the right
     * place would look correct to a recorder that keeps overwriting, and the
     * engines act on the first call — a transport that joined its loop on that
     * call is already in the registry, whatever happens afterwards. [attachCount]
     * pins the arity for the same reason.
     */
    private class AttachRecordingTransport : TestIoTransport() {
        var attachCount: Int = 0
        var readWiredAtAttach: Boolean? = null
        var readClosedWiredAtAttach: Boolean? = null
        var writabilityWiredAtAttach: Boolean? = null
        var connectionFailureWiredAtAttach: Boolean? = null

        override fun onChannelAttached() {
            attachCount++
            if (attachCount > 1) return
            readWiredAtAttach = onRead != null
            readClosedWiredAtAttach = onReadClosed != null
            writabilityWiredAtAttach = onWritabilityChanged != null
            connectionFailureWiredAtAttach = onConnectionFailure != null
        }
    }

    @Test
    fun `the attach hook runs only once every callback is wired`() {
        // Engines act on this hook: the POSIX ones join their loop's participant
        // registry from it, which is what decides whether they are told the loop
        // stopped -- and that notification is delivered once, straight into
        // `onReadClosed`. Called before the wiring, it would be spent on a null
        // and the connection would never learn, while its construction site saw
        // a registered transport and handed the caller a channel that would stay
        // silent for good. The same hook is where two engines arm their read
        // primitive, and a byte arriving through a null `onRead` is dropped.
        //
        // Nothing else pins the order: it is the position of one statement at
        // the end of this class's `init`, and moving it up is the kind of edit
        // that looks like tidying.
        val transport = AttachRecordingTransport()
        object : AbstractPipelinedChannel(transport, logger) {}

        assertEquals(1, transport.attachCount, "the attach hook must run exactly once")
        assertEquals(true, transport.readWiredAtAttach, "onRead must be wired before the attach hook")
        assertEquals(
            true,
            transport.readClosedWiredAtAttach,
            "onReadClosed must be wired before the attach hook",
        )
        assertEquals(
            true,
            transport.writabilityWiredAtAttach,
            "onWritabilityChanged must be wired before the attach hook",
        )
        assertEquals(
            true,
            transport.connectionFailureWiredAtAttach,
            "onConnectionFailure must be wired before the attach hook",
        )
    }

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
    fun `local close without prior peer-FIN tells the pipeline the connection ended`() {
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

        // It used to be that this only delegated to transport.close(), and
        // this case asserted the resulting silence. That expectation was a
        // description of the code rather than a decision about it: the commit
        // that added it was changing whether a *peer* FIN auto-closes a
        // channel, and said nothing about a local close notifying; and
        // `DefaultPipeline.notifyInactive`'s own KDoc names
        // `AbstractPipelinedChannel.close` as a caller it expects and is
        // idempotent for.
        //
        // The silence was not free. A handler that registers something on
        // `onActive` has only this to unregister it on, and no transport but
        // one reports a local close as a read close — so a connection the
        // server itself drops stayed registered forever.
        assertEquals(1, handler.inactiveCount, "a local close is an ending, and the handlers hear it once")
        assertFalse(channel.isOpen)
        assertEquals(1, transport.closeCount)
    }
}
