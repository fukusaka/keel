package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A handler that removes itself from inside one of its own callbacks does
 * not cut the walk that was passing through it.
 *
 * The context it was called on keeps its neighbours after the removal —
 * only the neighbours forget *it* — so propagating from it still reaches the
 * next handler, as it does in Netty. Nothing new is routed to the removed
 * context: it is off the chain the next walk starts from.
 *
 * Measured before this held: a handler removing itself from `onInactive` in
 * the middle of a chain left everything below it without the ending, and
 * one removing itself from `onClose` left everything above it without the
 * close — the shape in which a TLS handler's native session is never
 * released.
 */
class PipelineSelfRemovalTest {

    private val log = mutableListOf<String>()
    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("PipelineSelfRemovalTest")) {}
    private val pipeline: Pipeline get() = channel.pipeline

    private inner class Recorder(private val name: String) : DuplexHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            log.add("$name:read")
            ctx.propagateRead(msg)
        }

        override fun onInactive(ctx: PipelineHandlerContext) {
            log.add("$name:inactive")
            ctx.propagateInactive()
        }

        override fun onClose(ctx: PipelineHandlerContext) {
            log.add("$name:close")
            ctx.propagateClose()
        }

        override fun handlerRemoved(ctx: PipelineHandlerContext) {
            log.add("$name:removed")
        }
    }

    /** Removes itself on the named callback, then propagates as usual. */
    private inner class SelfRemover(private val name: String, private val on: String) : DuplexHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (on == "read") ctx.pipeline.remove(name)
            ctx.propagateRead(msg)
        }

        override fun onInactive(ctx: PipelineHandlerContext) {
            if (on == "inactive") ctx.pipeline.remove(name)
            ctx.propagateInactive()
        }

        override fun onClose(ctx: PipelineHandlerContext) {
            if (on == "close") ctx.pipeline.remove(name)
            ctx.propagateClose()
        }
    }

    private fun chain(mid: PipelineHandler) {
        pipeline.addLast("a", Recorder("a"))
        pipeline.addLast("mid", mid)
        pipeline.addLast("z", Recorder("z"))
        log.clear()
    }

    @Test
    fun `a handler that removes itself while handling the ending still passes it below`() {
        chain(SelfRemover("mid", on = "inactive"))

        pipeline.notifyInactive()

        assertEquals(listOf("a:inactive", "z:inactive"), log)
        assertNull(pipeline.get("mid"))
    }

    @Test
    fun `a handler that removes itself while handling a read still passes it below`() {
        chain(SelfRemover("mid", on = "read"))

        pipeline.notifyRead("payload")

        assertEquals(listOf("a:read", "z:read"), log)
    }

    @Test
    fun `a handler that removes itself while handling the close still passes it above`() {
        chain(SelfRemover("mid", on = "close"))

        pipeline.requestClose()

        // The walk runs tail → head: z first, then mid removes itself, then a
        // still hears it. The walk's completion is the pipeline's end of
        // life: the ending, then every handler removed, tail to head.
        assertEquals(listOf("z:close", "a:close", "a:inactive", "z:inactive", "z:removed", "a:removed"), log)
    }

    @Test
    fun `a sibling removed before the walk reaches it is skipped rather than invoked through a stale link`() {
        // `mid` removes itself and `z` in one go, then propagates. Its context
        // still links to z's — which is exactly how an in-flight walk keeps
        // going — but z's handlerRemoved has run, so z is skipped, as in Netty.
        val remover = object : DuplexHandler {
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                ctx.pipeline.remove("mid")
                ctx.pipeline.remove("z")
                ctx.propagateRead(msg)
            }

            override fun onInactive(ctx: PipelineHandlerContext) {
                ctx.pipeline.remove("mid")
                ctx.pipeline.remove("z")
                ctx.propagateInactive()
            }
        }
        chain(remover)

        pipeline.notifyRead("payload")
        assertEquals(listOf("a:read", "z:removed"), log)
        log.clear()

        pipeline.addLast("mid", remover)
        pipeline.addLast("z", Recorder("z"))
        log.clear()
        pipeline.notifyInactive()
        assertEquals(listOf("a:inactive", "z:removed"), log)
    }

    @Test
    fun `a write from a removed context skips a removed encoder and reaches the transport`() {
        val tracker = TrackingAllocator()
        var encoderWrites = 0
        var zCtx: PipelineHandlerContext? = null
        pipeline.addLast(
            "enc",
            object : OutboundHandler {
                override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
                    encoderWrites++
                    ctx.propagateWrite(msg)
                }
            },
        )
        pipeline.addLast(
            "z",
            object : OutboundHandler {
                override fun handlerAdded(ctx: PipelineHandlerContext) {
                    zCtx = ctx
                }

                override fun onWrite(ctx: PipelineHandlerContext, msg: Any) = ctx.propagateWrite(msg)
            },
        )
        pipeline.remove("z")
        pipeline.remove("enc")

        checkNotNull(zCtx).propagateWrite(tracker.allocate(8).also { it.writerIndex = 4 })

        assertEquals(0, encoderWrites, "an encoder whose handlerRemoved has run is not asked to encode")
        assertEquals(1, transport.written.size, "the write still reaches the transport")
        transport.close()
        assertEquals(0, tracker.outstandingCount)
    }

    @Test
    fun `what a handler forwards after replacing itself reaches its replacement`() {
        // Netty's replace points the old context at the new one in both
        // directions, so an upgrade decoder handing on the bytes it did not
        // consume reaches the handler that took its place.
        pipeline.addLast(
            "a",
            object : InboundHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    ctx.pipeline.replace("a", "b", Recorder("b"))
                    ctx.propagateRead(msg)
                }
            },
        )
        pipeline.addLast("z", Recorder("z"))
        log.clear()

        pipeline.notifyRead("payload")

        assertEquals(listOf("b:read", "z:read"), log)
    }

    @Test
    fun `nothing after the removal is routed to the removed handler`() {
        chain(Recorder("mid"))
        pipeline.remove("mid")
        assertEquals(listOf("mid:removed"), log)
        log.clear()

        pipeline.notifyRead("payload")
        pipeline.notifyInactive()
        pipeline.requestClose()

        assertEquals(
            listOf("a:read", "z:read", "a:inactive", "z:inactive", "z:close", "a:close", "z:removed", "a:removed"),
            log,
        )
        assertNull(pipeline.get("mid"))
    }
}
