package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A lifecycle sweep — activation, ending, close — does not stop at a handler
 * that throws.
 *
 * The throw goes to the handlers below as an error, and the sweep goes on
 * from there: if the thrower had not propagated the event before it threw,
 * the pipeline propagates it on the thrower's behalf; if it had, nothing is
 * added, so nobody hears the event twice. Data events are different — a read
 * that a handler throws on is released and reaches nobody below, as in Netty
 * — and one case here pins that they stay different.
 *
 * Measured before this held: a handler throwing from `onInactive` above the
 * server's registry handler left the connection registered after it was
 * gone, and one throwing from `onActive` on a deferred-drain engine left it
 * out of the registry for good, invisible to the server's own stop.
 */
class PipelineLifecycleSweepTest {

    /** Records what it hears under its own name, and passes everything on. */
    private open class Recorder(private val name: String, private val log: MutableList<String>) : DuplexHandler {
        override fun onActive(ctx: PipelineHandlerContext) {
            log.add("$name:active")
            ctx.propagateActive()
        }

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            log.add("$name:read")
            ctx.propagateRead(msg)
        }

        override fun onInactive(ctx: PipelineHandlerContext) {
            log.add("$name:inactive")
            ctx.propagateInactive()
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            log.add("$name:error")
            ctx.propagateError(cause)
        }

        override fun onClose(ctx: PipelineHandlerContext) {
            log.add("$name:close")
            ctx.propagateClose()
        }
    }

    /**
     * Throws from the callbacks it is told to. With [propagateFirst] it
     * passes the event on before throwing — the shape the sweep must not
     * deliver twice.
     */
    private class Thrower(
        private val throwOn: Set<String>,
        private val propagateFirst: Boolean = false,
    ) : DuplexHandler {
        override fun onActive(ctx: PipelineHandlerContext) {
            if (propagateFirst) ctx.propagateActive()
            if ("active" in throwOn) throw IllegalStateException("active")
            if (!propagateFirst) ctx.propagateActive()
        }

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if ("read" in throwOn) throw IllegalStateException("read")
            ctx.propagateRead(msg)
        }

        override fun onInactive(ctx: PipelineHandlerContext) {
            if (propagateFirst) ctx.propagateInactive()
            if ("inactive" in throwOn) throw IllegalStateException("inactive")
            if (!propagateFirst) ctx.propagateInactive()
        }

        override fun onClose(ctx: PipelineHandlerContext) {
            if (propagateFirst) ctx.propagateClose()
            if ("close" in throwOn) throw IllegalStateException("close")
            if (!propagateFirst) ctx.propagateClose()
        }
    }

    private class Fixture(deferDrain: Boolean) {
        val log = mutableListOf<String>()
        val queue = QueueingDispatcher()
        val transport = TestIoTransport().apply { if (deferDrain) dispatcher = queue }
        val channel: PipelinedChannel = object : AbstractPipelinedChannel(transport, PrintLogger("sweep")) {}
        val pipeline: Pipeline get() = channel.pipeline

        /** Installs `above` (a thrower) over `below` (a recorder), then lets any deferred drain run. */
        fun install(above: PipelineHandler, below: PipelineHandler = Recorder("below", log)) {
            pipeline.addLast("above", above)
            pipeline.addLast("below", below)
            queue.runQueued()
        }
    }

    // --- the ending ---

    @Test
    fun `a handler that throws on the ending does not hide it from the handlers below`() {
        val f = Fixture(deferDrain = false)
        f.install(Thrower(setOf("inactive")))
        f.log.clear()

        f.pipeline.notifyInactive()

        assertEquals(listOf("below:error", "below:inactive"), f.log, "the reason, then the ending it interrupted")
    }

    @Test
    fun `a handler that propagates the ending and then throws does not make it arrive twice`() {
        val f = Fixture(deferDrain = false)
        f.install(Thrower(setOf("inactive"), propagateFirst = true))
        f.log.clear()

        f.pipeline.notifyInactive()

        assertEquals(listOf("below:inactive", "below:error"), f.log, "the ending it already passed on, then the reason")
    }

    // --- the activation, on an engine that defers the first sweep ---

    @Test
    fun `a handler that throws on activation does not hide it from the handlers below`() {
        // The drain is deferred, so the whole chain is assembled before the
        // activation sweeps it — the readiness engines' shape, where a throw
        // above the registry handler kept the connection out of the registry.
        val f = Fixture(deferDrain = true)
        f.install(Thrower(setOf("active")))

        assertEquals(listOf("below:error", "below:active"), f.log)
    }

    @Test
    fun `a handler that propagates the activation and then throws does not make it arrive twice`() {
        val f = Fixture(deferDrain = true)
        f.install(Thrower(setOf("active"), propagateFirst = true))

        assertEquals(listOf("below:active", "below:error"), f.log)
    }

    @Test
    fun `a throwing handler on a deferred-drain engine hides neither the activation nor the ending`() {
        // Both directions of the registry's join / leave on one connection.
        val f = Fixture(deferDrain = true)
        f.install(Thrower(setOf("active", "inactive")))
        assertEquals(listOf("below:error", "below:active"), f.log)
        f.log.clear()

        f.pipeline.notifyInactive()

        assertEquals(listOf("below:error", "below:inactive"), f.log)
    }

    // --- the close walk (outbound: the handler above hears it) ---

    @Test
    fun `a handler that throws on close does not hide the close from the handlers above it`() {
        val f = Fixture(deferDrain = false)
        // Outbound walks tail → head, so the thrower must sit on the tail
        // side and the recorder on the head side.
        f.pipeline.addLast("above", Recorder("above", f.log))
        f.pipeline.addLast("thrower", Thrower(setOf("close")))
        f.log.clear()

        f.pipeline.requestClose()

        // The reason travels inbound from the thrower, toward the tail, so
        // the handler above it hears the close it is owed and not the error.
        // The ending after it is the walk's own end of life: a close walk
        // that completes ends the pipeline, and the ending is delivered
        // before the handlers are removed.
        assertEquals(listOf("above:close", "above:inactive"), f.log)
    }

    @Test
    fun `a handler that propagates the close and then throws does not make it arrive twice`() {
        val f = Fixture(deferDrain = false)
        f.pipeline.addLast("above", Recorder("above", f.log))
        f.pipeline.addLast("thrower", Thrower(setOf("close"), propagateFirst = true))
        f.log.clear()

        f.pipeline.requestClose()

        assertEquals(listOf("above:close", "above:inactive"), f.log)
    }

    // --- a handler installed from inside a sweep hears it once ---

    @Test
    fun `a handler installed from inside the activation sweep hears the activation once`() {
        // The sweep reaches the new handler on its own; catching it up as
        // well delivered the activation twice.
        val f = Fixture(deferDrain = true)
        val installer = object : InboundHandler {
            override fun onActive(ctx: PipelineHandlerContext) {
                ctx.pipeline.addLast("inner", Recorder("inner", f.log))
                ctx.propagateActive()
            }
        }
        f.pipeline.addLast("installer", installer)
        f.queue.runQueued()

        assertEquals(listOf("inner:active"), f.log)
    }

    @Test
    fun `a handler installed from inside the ending sweep hears the ending once`() {
        val f = Fixture(deferDrain = false)
        val installer = object : InboundHandler {
            override fun onInactive(ctx: PipelineHandlerContext) {
                ctx.pipeline.addLast("inner", Recorder("inner", f.log))
                ctx.propagateInactive()
            }
        }
        f.pipeline.addLast("installer", installer)
        f.log.clear()

        f.pipeline.notifyInactive()

        assertEquals(listOf("inner:inactive"), f.log)
    }

    @Test
    fun `a handler installed above the sweep's position from inside it is caught up`() {
        // Inserted upstream, where the sweep will not pass: the replay is
        // still owed, and is the only delivery.
        val f = Fixture(deferDrain = true)
        val installer = object : InboundHandler {
            override fun onActive(ctx: PipelineHandlerContext) {
                ctx.pipeline.addFirst("outer", Recorder("outer", f.log))
                ctx.propagateActive()
            }
        }
        f.pipeline.addLast("installer", installer)
        f.queue.runQueued()

        assertEquals(listOf("outer:active"), f.log)
    }

    @Test
    fun `a handler installed below the sweep after it propagated is caught up`() {
        // The sweep has passed by the time the new handler exists, so the
        // replay is the only delivery — and must not be skipped for a context
        // the sweep would have reached had it not gone already.
        val f = Fixture(deferDrain = true)
        val installer = object : InboundHandler {
            override fun onActive(ctx: PipelineHandlerContext) {
                ctx.propagateActive()
                ctx.pipeline.addLast("inner", Recorder("inner", f.log))
            }
        }
        f.pipeline.addLast("installer", installer)
        f.queue.runQueued()
        assertEquals(listOf("inner:active"), f.log)
        f.log.clear()

        val ender = object : InboundHandler {
            override fun onInactive(ctx: PipelineHandlerContext) {
                ctx.propagateInactive()
                ctx.pipeline.addLast("late", Recorder("late", f.log))
            }
        }
        f.pipeline.addFirst("ender", ender)
        f.log.clear()
        f.pipeline.notifyInactive()
        assertEquals(listOf("inner:inactive", "late:inactive"), f.log)
    }

    @Test
    fun `a handler that replaces itself after propagating leaves its replacement caught up`() {
        val f = Fixture(deferDrain = false)
        f.pipeline.addLast(
            "a",
            object : InboundHandler {
                override fun onInactive(ctx: PipelineHandlerContext) {
                    ctx.propagateInactive()
                    ctx.pipeline.replace("a", "b", Recorder("b", f.log))
                }
            },
        )
        f.log.clear()

        f.pipeline.notifyInactive()

        assertEquals(listOf("b:inactive"), f.log)
    }

    @Test
    fun `an ending journalled before the drain does not withhold the activation from the handlers below a thrower`() {
        // The ending was observed before the sweep began (a peer FIN ahead of
        // the deferred drain), not raised by the thrower: the drain delivers
        // the activation first and the ending after, past the throw.
        val f = Fixture(deferDrain = true)
        f.pipeline.addLast("above", Thrower(setOf("active")))
        f.pipeline.addLast("below", Recorder("below", f.log))
        f.pipeline.notifyInactive()
        f.queue.runQueued()

        assertEquals(listOf("below:error", "below:active", "below:inactive"), f.log)
    }

    @Test
    fun `a handler that closes the channel and throws on activation over a journalled ending gets no activation delivered on its behalf`() {
        // The ending was journalled before the drain, so its own observation
        // cannot tell this close from it; the walk the close ran can.
        val f = Fixture(deferDrain = true)
        val gate = object : InboundHandler {
            override fun onActive(ctx: PipelineHandlerContext) {
                ctx.channel.close()
                throw IllegalStateException("refused")
            }
        }
        f.pipeline.addLast("above", gate)
        f.pipeline.addLast("below", Recorder("below", f.log))
        f.pipeline.notifyInactive()
        f.queue.runQueued()

        // The channel's close delivers the ending it finds observed before it
        // walks the close: ending, close, then the reason — and no activation
        // on the thrower's behalf after them.
        assertEquals(listOf("below:inactive", "below:close", "below:error"), f.log)
    }

    @Test
    fun `no activation is delivered on behalf of a thrower once a handler above it ended the connection in the same sweep`() {
        val f = Fixture(deferDrain = true)
        val ender = object : InboundHandler {
            override fun onActive(ctx: PipelineHandlerContext) {
                ctx.channel.close()
                ctx.propagateActive()
            }
        }
        f.pipeline.addLast("ender", ender)
        f.pipeline.addLast("thrower", Thrower(setOf("active")))
        f.pipeline.addLast("below", Recorder("below", f.log))
        f.queue.runQueued()

        // The activation stops at the thrower: once the ending was delivered
        // no context is activated, so the thrower is not even asked, and
        // nothing is delivered on its behalf either.
        assertEquals(listOf("below:inactive", "below:close"), f.log)
    }

    // --- no activation on behalf of a handler that already ended the connection ---

    @Test
    fun `a handler that ends the connection and then throws on activation does not get an activation delivered on its behalf`() {
        val f = Fixture(deferDrain = true)
        val gate = object : InboundHandler {
            override fun onActive(ctx: PipelineHandlerContext) {
                ctx.channel.close()
                throw IllegalStateException("refused")
            }
        }
        f.install(gate)

        assertEquals(
            listOf("below:inactive", "below:close", "below:error"),
            f.log,
            "the ending, the close, the reason — and no activation after them",
        )
    }

    // --- data is not lifecycle ---

    @Test
    fun `a handler that throws on a read still keeps the read from the handlers below`() {
        val f = Fixture(deferDrain = false)
        f.install(Thrower(setOf("read")))
        f.log.clear()

        f.pipeline.notifyRead("payload")

        assertEquals(listOf("below:error"), f.log, "a read the handler could not process reaches nobody below")
    }
}
