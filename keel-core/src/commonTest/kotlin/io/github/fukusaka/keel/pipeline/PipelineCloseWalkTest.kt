package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the close walk owes whoever asked for it, whatever the handlers do.
 *
 * The walk ends at the head, and the head is what releases the descriptor. So
 * every way a handler can stop the walk short is a way to strand a connection:
 * throwing from `onClose` (which the invoker catches and diverts *inbound*,
 * away from the head), overriding `onClose` without propagating, or removing
 * itself so the walk finds no previous link. None of those are exotic — the
 * first is any cleanup that fails, and the second and third are the documented
 * extension points.
 *
 * The other half is the failure the head itself reports. A transport whose
 * teardown refuses raises, deliberately, and that answer belongs to whoever
 * asked to close — not to a warning at the end of the inbound chain. Told
 * apart from a handler's own failure by type rather than by position, because
 * a handler's `onClose` calls `propagateClose()` from inside its own body:
 * there is no frame that sees only one of the two.
 *
 * These drive [Pipeline.requestClose] directly. Nothing in the library calls
 * it yet — the channel closes its transport itself — so this is the entry a
 * caller reaching for it today would use, and the ground a channel would need
 * before it could.
 */
class PipelineCloseWalkTest {

    private val logger = PrintLogger("PipelineCloseWalkTest")

    /**
     * Refuses to be torn down, the way a failing `close(2)` release does —
     * once.
     *
     * Once is the realistic part, and it is load-bearing. A transport claims
     * the transition before it does the work (`markClosing`), so only the
     * claiming call can reach a teardown at all; every later one returns
     * silently. A double that refuses every time is refused by the *second*
     * ask as readily as the first, and then a caller sees the refusal whether
     * or not the walk carried it — measured, and it made this case pass
     * against a head that did not carry it.
     */
    private class RefusingTransport : TestIoTransport() {
        private var refusedOnce = false

        override fun close() {
            super.close()
            if (!refusedOnce) {
                refusedOnce = true
                throw IllegalStateException(REFUSAL)
            }
        }
    }

    private fun channelOver(transport: TestIoTransport): PipelinedChannel =
        object : AbstractPipelinedChannel(transport, logger) {}

    @Test
    fun `a handler that throws from onClose does not keep the descriptor`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        channel.pipeline.addLast(
            "throws",
            object : DuplexHandler {
                override fun onClose(ctx: PipelineHandlerContext): Unit = throw IllegalStateException("cleanup failed")
            },
        )

        channel.pipeline.requestClose()

        assertTrue(
            transport.closed,
            "the walk stopped at the handler, and the descriptor is still released — it is not a handler's to keep",
        )
    }

    @Test
    fun `a handler that does not propagate does not keep the descriptor`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        // Overriding without propagating is a supported shape — it is how a
        // handler holds a close back — so it must not be a way to lose one.
        channel.pipeline.addLast(
            "swallows",
            object : DuplexHandler {
                override fun onClose(ctx: PipelineHandlerContext) = Unit
            },
        )

        channel.pipeline.requestClose()

        assertTrue(transport.closed, "a close that is held back at a handler still reaches the transport")
    }

    @Test
    fun `a handler that removes itself while closing does not keep the descriptor`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        channel.pipeline.addLast(
            "leaves",
            object : DuplexHandler {
                override fun onClose(ctx: PipelineHandlerContext) {
                    // Removal unlinks this context, so the walk that resumes
                    // from it finds no previous link and ends here.
                    channel.pipeline.remove("leaves")
                    ctx.propagateClose()
                }
            },
        )

        channel.pipeline.requestClose()

        assertTrue(transport.closed, "a handler that leaves mid-walk does not take the close with it")
    }

    @Test
    fun `a teardown that refuses reaches whoever asked to close`() {
        val transport = RefusingTransport()
        val channel = channelOver(transport)
        channel.pipeline.addLast("passes-it-on", object : DuplexHandler {})

        val raised = runCatching { channel.pipeline.requestClose() }.exceptionOrNull()

        // Through a handler, because that is where it would be caught: the
        // invoker's catch sits around a handler's whole body, propagateClose()
        // included, so a refusal from below arrives inside it.
        assertEquals(
            REFUSAL,
            raised?.message,
            "the refused release reaches the caller rather than becoming a warning at the end of the chain",
        )
    }

    @Test
    fun `a handler's own failure still travels the chain`() {
        val transport = TestIoTransport()
        val channel = channelOver(transport)
        val seen = mutableListOf<Throwable>()
        // The watcher goes after the thrower, because a handler's failure
        // travels *inbound* — towards the tail — while the close travels the
        // other way. Added the other way round it never hears it, which is
        // what this case was measuring before the order was fixed.
        channel.pipeline.addLast(
            "throws",
            object : DuplexHandler {
                override fun onClose(ctx: PipelineHandlerContext): Unit = throw IllegalStateException("cleanup failed")
            },
        )
        channel.pipeline.addLast(
            "watches",
            object : DuplexHandler {
                override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                    seen.add(cause)
                }
            },
        )

        channel.pipeline.requestClose()

        // The half that must not change: a handler failing is still the
        // chain's business. Only the terminus's own refusal is taken out of
        // that path, and this pins that the two are told apart rather than
        // both being routed one way.
        assertEquals(1, seen.size, "the handler's failure is reported to the chain")
        assertEquals("cleanup failed", seen[0].message)
        assertFalse(transport.isOpen, "and the descriptor is released regardless")
    }

    private companion object {
        const val REFUSAL = "the release refused"
    }
}
