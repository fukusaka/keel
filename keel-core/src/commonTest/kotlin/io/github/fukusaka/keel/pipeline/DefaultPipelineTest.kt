package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultPipelineTest {

    // --- Test infrastructure ---

    private val logger = PrintLogger("test")

    private val transport = object : IoTransport {
        val written = mutableListOf<IoBuf>()
        var flushed = false
        var closed = false
        override fun write(buf: IoBuf) { written.add(buf) }
        override fun flush(): Boolean { flushed = true; return true }
        override var onFlushComplete: (() -> Unit)? = null
        override fun close() { closed = true }
    }

    private val channel = object : PipelinedChannel {
        override lateinit var pipeline: Pipeline
        override val isActive: Boolean = true
        override val isWritable: Boolean = true
        override val allocator: BufferAllocator get() = error("not needed in tests")
        override fun ensureBridge(): SuspendBridgeHandler = error("not needed in tests")
    }

    private fun createPipeline(): Pipeline {
        val pipeline = DefaultPipeline(channel, transport, logger)
        channel.pipeline = pipeline
        return pipeline
    }

    // --- Recording handler ---

    private class RecordingInboundHandler(
        override val acceptedType: KClass<*> = Any::class,
        override val producedType: KClass<*> = Any::class,
    ) : InboundHandler {
        val events = mutableListOf<String>()
        var lastMsg: Any? = null

        override fun onActive(ctx: PipelineHandlerContext) {
            events.add("active")
            ctx.propagateActive()
        }

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            events.add("read")
            lastMsg = msg
            ctx.propagateRead(msg)
        }

        override fun onReadComplete(ctx: PipelineHandlerContext) {
            events.add("readComplete")
            ctx.propagateReadComplete()
        }

        override fun onInactive(ctx: PipelineHandlerContext) {
            events.add("inactive")
            ctx.propagateInactive()
        }

        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            events.add("error:${cause.message}")
            ctx.propagateError(cause)
        }

        override fun onUserEvent(ctx: PipelineHandlerContext, event: Any) {
            events.add("userEvent:$event")
            ctx.propagateUserEvent(event)
        }
    }

    private class RecordingOutboundHandler : OutboundHandler {
        val events = mutableListOf<String>()
        var lastMsg: Any? = null

        override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
            events.add("write")
            lastMsg = msg
            ctx.propagateWrite(msg)
        }

        override fun onFlush(ctx: PipelineHandlerContext) {
            events.add("flush")
            ctx.propagateFlush()
        }

        override fun onClose(ctx: PipelineHandlerContext) {
            events.add("close")
            ctx.propagateClose()
        }
    }

    // --- addLast / addFirst / remove / replace ---

    @Test
    fun `addLast adds handler before TAIL`() {
        val pipeline = createPipeline()
        val handler = RecordingInboundHandler()
        pipeline.addLast("h1", handler)
        assertNotNull(pipeline.get("h1"))
        assertEquals(handler, pipeline.get("h1"))
    }

    @Test
    fun `addFirst adds handler after HEAD`() {
        val pipeline = createPipeline()
        val h1 = RecordingInboundHandler()
        val h2 = RecordingInboundHandler()
        pipeline.addLast("h2", h2)
        pipeline.addFirst("h1", h1)

        // Verify order: HEAD → h1 → h2 → TAIL
        pipeline.notifyRead("msg")
        assertEquals(listOf("read"), h1.events)
        assertEquals(listOf("read"), h2.events)
    }

    @Test
    fun `addBefore inserts handler before target`() {
        val pipeline = createPipeline()
        val h1 = RecordingInboundHandler()
        val h2 = RecordingInboundHandler()
        pipeline.addLast("h2", h2)
        pipeline.addBefore("h2", "h1", h1)

        pipeline.notifyRead("msg")
        assertEquals(listOf("read"), h1.events)
        assertEquals(listOf("read"), h2.events)
    }

    @Test
    fun `addAfter inserts handler after target`() {
        val pipeline = createPipeline()
        val h1 = RecordingInboundHandler()
        val h2 = RecordingInboundHandler()
        pipeline.addLast("h1", h1)
        pipeline.addAfter("h1", "h2", h2)

        pipeline.notifyRead("msg")
        assertEquals(listOf("read"), h1.events)
        assertEquals(listOf("read"), h2.events)
    }

    @Test
    fun `remove handler`() {
        val pipeline = createPipeline()
        val handler = RecordingInboundHandler()
        pipeline.addLast("h1", handler)
        pipeline.remove("h1")
        assertNull(pipeline.get("h1"))
    }

    @Test
    fun `replace handler`() {
        val pipeline = createPipeline()
        val h1 = RecordingInboundHandler()
        val h2 = RecordingInboundHandler()
        pipeline.addLast("h1", h1)
        val old = pipeline.replace("h1", "h2", h2)
        assertEquals(h1, old)
        assertNull(pipeline.get("h1"))
        assertNotNull(pipeline.get("h2"))
    }

    @Test
    fun `duplicate name throws`() {
        val pipeline = createPipeline()
        pipeline.addLast("h1", RecordingInboundHandler())
        assertFailsWith<IllegalArgumentException> {
            pipeline.addLast("h1", RecordingInboundHandler())
        }
    }

    @Test
    fun `remove non-existent throws`() {
        val pipeline = createPipeline()
        assertFailsWith<NoSuchElementException> {
            pipeline.remove("non-existent")
        }
    }

    // --- Inbound event propagation ---

    @Test
    fun `notifyRead propagates through handlers in order`() {
        val pipeline = createPipeline()
        val h1 = RecordingInboundHandler()
        val h2 = RecordingInboundHandler()
        pipeline.addLast("h1", h1)
        pipeline.addLast("h2", h2)

        pipeline.notifyRead("hello")

        assertEquals(listOf("read"), h1.events)
        assertEquals("hello", h1.lastMsg)
        assertEquals(listOf("read"), h2.events)
        assertEquals("hello", h2.lastMsg)
    }

    @Test
    fun `notifyActive propagates through handlers`() {
        val pipeline = createPipeline()
        val handler = RecordingInboundHandler()
        pipeline.addLast("h1", handler)
        pipeline.notifyActive()
        assertEquals(listOf("active"), handler.events)
    }

    @Test
    fun `notifyInactive propagates through handlers`() {
        val pipeline = createPipeline()
        val handler = RecordingInboundHandler()
        pipeline.addLast("h1", handler)
        pipeline.notifyInactive()
        assertEquals(listOf("inactive"), handler.events)
    }

    @Test
    fun `notifyReadComplete propagates through handlers`() {
        val pipeline = createPipeline()
        val handler = RecordingInboundHandler()
        pipeline.addLast("h1", handler)
        pipeline.notifyReadComplete()
        assertEquals(listOf("readComplete"), handler.events)
    }

    @Test
    fun `notifyError propagates through handlers`() {
        val pipeline = createPipeline()
        val handler = RecordingInboundHandler()
        pipeline.addLast("h1", handler)
        pipeline.notifyError(RuntimeException("test"))
        assertEquals(listOf("error:test"), handler.events)
    }

    // --- Outbound event propagation ---

    @Test
    fun `requestWrite propagates through outbound handler to transport`() {
        val pipeline = createPipeline()
        val handler = RecordingOutboundHandler()
        pipeline.addLast("h1", handler)

        pipeline.requestWrite("response")
        assertEquals(listOf("write"), handler.events)
        assertEquals("response", handler.lastMsg)
    }

    @Test
    fun `requestFlush propagates to transport`() {
        val pipeline = createPipeline()
        val handler = RecordingOutboundHandler()
        pipeline.addLast("h1", handler)

        pipeline.requestFlush()
        assertEquals(listOf("flush"), handler.events)
        assertTrue(transport.flushed)
    }

    @Test
    fun `requestClose propagates to transport`() {
        val pipeline = createPipeline()
        val handler = RecordingOutboundHandler()
        pipeline.addLast("h1", handler)

        pipeline.requestClose()
        assertEquals(listOf("close"), handler.events)
        assertTrue(transport.closed)
    }

    // --- Inbound handler skips non-inbound contexts ---

    @Test
    fun `inbound events skip outbound-only handlers`() {
        val pipeline = createPipeline()
        val outbound = RecordingOutboundHandler()
        val inbound = RecordingInboundHandler()
        pipeline.addLast("out", outbound)
        pipeline.addLast("in", inbound)

        pipeline.notifyRead("msg")
        assertTrue(outbound.events.isEmpty())
        assertEquals(listOf("read"), inbound.events)
    }

    @Test
    fun `outbound events skip inbound-only handlers`() {
        val pipeline = createPipeline()
        val inbound = RecordingInboundHandler()
        val outbound = RecordingOutboundHandler()
        pipeline.addLast("in", inbound)
        pipeline.addLast("out", outbound)

        pipeline.requestFlush()
        // Flush does not produce error events, so inbound handler should not be triggered.
        val inboundNonErrorEvents = inbound.events.filter { !it.startsWith("error:") }
        assertTrue(inboundNonErrorEvents.isEmpty())
        assertEquals(listOf("flush"), outbound.events)
    }

    // --- Message transformation ---

    @Test
    fun `handler can transform message`() {
        val pipeline = createPipeline()
        val transformer = object : InboundHandler {
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                ctx.propagateRead("transformed:$msg")
            }
        }
        val receiver = RecordingInboundHandler()
        pipeline.addLast("transform", transformer)
        pipeline.addLast("receive", receiver)

        pipeline.notifyRead("original")
        assertEquals("transformed:original", receiver.lastMsg)
    }

    // --- Exception handling ---

    @Test
    fun `exception in onRead propagates as error`() {
        val pipeline = createPipeline()
        val failing = object : InboundHandler {
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                throw RuntimeException("parse error")
            }
        }
        val errorHandler = RecordingInboundHandler()
        pipeline.addLast("fail", failing)
        pipeline.addLast("errors", errorHandler)

        pipeline.notifyRead("data")
        assertEquals(listOf("error:parse error"), errorHandler.events)
    }

    // --- Type chain validation ---

    // Typed test handlers
    private class StringProducer : InboundHandler {
        override val producedType: KClass<*> = String::class
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            ctx.propagateRead(msg.toString())
        }
    }

    private class StringConsumer : InboundHandler {
        override val acceptedType: KClass<*> = String::class
        val received = mutableListOf<String>()
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            received.add(msg as String)
        }
    }

    private class IntConsumer : InboundHandler {
        override val acceptedType: KClass<*> = Int::class
    }

    @Test
    fun `type chain validation succeeds with matching types`() {
        val pipeline = createPipeline()
        pipeline.addLast("producer", StringProducer())
        pipeline.addLast("consumer", StringConsumer())
        // No exception
    }

    @Test
    fun `type chain validation fails with mismatching types`() {
        val pipeline = createPipeline()
        pipeline.addLast("producer", StringProducer())
        assertFailsWith<PipelineTypeException> {
            pipeline.addLast("consumer", IntConsumer())
        }
    }

    @Test
    fun `type chain validation skips when type is Any`() {
        val pipeline = createPipeline()
        // Default acceptedType/producedType = Any::class → no validation
        pipeline.addLast("h1", RecordingInboundHandler())
        pipeline.addLast("h2", RecordingInboundHandler())
        // No exception
    }

    @Test
    fun `replace validates type chain with neighbors`() {
        val pipeline = createPipeline()
        pipeline.addLast("producer", StringProducer())
        pipeline.addLast("consumer", StringConsumer())

        // Replace consumer with IntConsumer → type mismatch
        assertFailsWith<PipelineTypeException> {
            pipeline.replace("consumer", "int-consumer", IntConsumer())
        }
    }

    // --- handlerAdded / handlerRemoved lifecycle ---

    @Test
    fun `handlerAdded called on addLast`() {
        val pipeline = createPipeline()
        var added = false
        val handler = object : InboundHandler {
            override fun handlerAdded(ctx: PipelineHandlerContext) { added = true }
        }
        pipeline.addLast("h1", handler)
        assertTrue(added)
    }

    @Test
    fun `handlerRemoved called on remove`() {
        val pipeline = createPipeline()
        var removed = false
        val handler = object : InboundHandler {
            override fun handlerRemoved(ctx: PipelineHandlerContext) { removed = true }
        }
        pipeline.addLast("h1", handler)
        pipeline.remove("h1")
        assertTrue(removed)
    }

    // --- context() ---

    @Test
    fun `context returns context for existing handler`() {
        val pipeline = createPipeline()
        val handler = RecordingInboundHandler()
        pipeline.addLast("h1", handler)

        val ctx = pipeline.context("h1")
        assertNotNull(ctx)
        assertEquals("h1", ctx.name)
        assertEquals(handler, ctx.handler)
    }

    @Test
    fun `context returns null for non-existent handler`() {
        val pipeline = createPipeline()
        assertNull(pipeline.context("non-existent"))
    }

    // --- User event propagation ---

    @Test
    fun `notifyUserEvent propagates through inbound handlers`() {
        val pipeline = createPipeline()
        val h1 = RecordingInboundHandler()
        val h2 = RecordingInboundHandler()
        pipeline.addLast("h1", h1)
        pipeline.addLast("h2", h2)

        pipeline.notifyUserEvent("handshake-complete")

        assertEquals(listOf("userEvent:handshake-complete"), h1.events)
        assertEquals(listOf("userEvent:handshake-complete"), h2.events)
    }

    @Test
    fun `notifyUserEvent skips outbound-only handlers`() {
        val pipeline = createPipeline()
        val outbound = RecordingOutboundHandler()
        val inbound = RecordingInboundHandler()
        pipeline.addLast("out", outbound)
        pipeline.addLast("in", inbound)

        pipeline.notifyUserEvent("event")

        assertTrue(outbound.events.isEmpty())
        assertEquals(listOf("userEvent:event"), inbound.events)
    }

    @Test
    fun `userEvent handler can consume event without propagating`() {
        val pipeline = createPipeline()
        val consumer = object : InboundHandler {
            var received: Any? = null
            override fun onUserEvent(ctx: PipelineHandlerContext, event: Any) {
                received = event
                // Do not propagate
            }
        }
        val downstream = RecordingInboundHandler()
        pipeline.addLast("consumer", consumer)
        pipeline.addLast("downstream", downstream)

        pipeline.notifyUserEvent("consumed")

        assertEquals("consumed", consumer.received)
        assertTrue(downstream.events.isEmpty())
    }

    @Test
    fun `userEvent exception propagates as error`() {
        val pipeline = createPipeline()
        val failing = object : InboundHandler {
            override fun onUserEvent(ctx: PipelineHandlerContext, event: Any) {
                throw RuntimeException("event error")
            }
        }
        val errorHandler = RecordingInboundHandler()
        pipeline.addLast("fail", failing)
        pipeline.addLast("errors", errorHandler)

        pipeline.notifyUserEvent("bad-event")

        assertEquals(listOf("error:event error"), errorHandler.events)
    }
}
