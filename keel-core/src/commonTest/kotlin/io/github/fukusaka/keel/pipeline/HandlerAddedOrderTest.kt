package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `handlerAdded` runs before the first lifecycle event a handler is told
 * about, on every engine.
 *
 * On an engine that drains the pre-attach journal inline (Netty,
 * NWConnection — and this test's transport), the first inbound handler used
 * to be swept by the activation *inside* its own `addLast`, before its
 * `handlerAdded` ran: measured `[onActive, handlerAdded]` for the first
 * handler and `[handlerAdded, onActive]` for every later one. A handler that
 * sets itself up in `handlerAdded` — its context, most of all — was then
 * activated before it had done so.
 */
class HandlerAddedOrderTest {

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("HandlerAddedOrderTest")) {}

    private class Recorder(private val name: String, private val log: MutableList<String>) : InboundHandler {
        override fun handlerAdded(ctx: PipelineHandlerContext) {
            log.add("$name:added")
        }

        override fun onActive(ctx: PipelineHandlerContext) {
            log.add("$name:active")
            ctx.propagateActive()
        }
    }

    @Test
    fun `the first handler is added before it is activated`() {
        val log = mutableListOf<String>()

        channel.pipeline.addLast("first", Recorder("first", log))

        assertEquals(listOf("first:added", "first:active"), log)
    }

    @Test
    fun `a handler that installs the rest of the stack from handlerAdded is added before anything is activated`() {
        val log = mutableListOf<String>()
        val installer = object : InboundHandler {
            override fun handlerAdded(ctx: PipelineHandlerContext) {
                log.add("init:added")
                ctx.pipeline.addLast("inner", Recorder("inner", log))
            }

            override fun onActive(ctx: PipelineHandlerContext) {
                log.add("init:active")
                ctx.propagateActive()
            }
        }

        channel.pipeline.addLast("init", installer)

        // Each handler is added before it is activated, and activated once.
        assertEquals(listOf("init:added", "inner:added", "init:active", "inner:active"), log)
    }

    @Test
    fun `a handler that installs the rest of the stack from handlerAdded has returned from it before it is activated`() {
        // The drain the inner add triggers is inline on this transport; it
        // waits for the outermost add to return rather than sweeping the
        // installer while its own handlerAdded is still on the stack.
        val log = mutableListOf<String>()
        val installer = object : InboundHandler {
            var ready = false
            override fun handlerAdded(ctx: PipelineHandlerContext) {
                ctx.pipeline.addLast("inner", Recorder("inner", log))
                ready = true
            }

            override fun onActive(ctx: PipelineHandlerContext) {
                log.add("init:active:ready=$ready")
                ctx.propagateActive()
            }
        }

        channel.pipeline.addLast("init", installer)

        assertEquals(listOf("inner:added", "init:active:ready=true", "inner:active"), log)
    }
}
