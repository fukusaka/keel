package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TypedInboundHandlerTest {

    private val logger = PrintLogger("test")

    private val transport = object : IoTransport {
        override fun write(buf: io.github.fukusaka.keel.buf.IoBuf) {}
        override fun flush(): Boolean = true
        override var onFlushComplete: (() -> Unit)? = null
        override fun close() {}
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

    // --- Type matching ---

    @Test
    fun `typed handler receives matching messages`() {
        val pipeline = createPipeline()
        val received = mutableListOf<String>()
        val handler = object : TypedInboundHandler<String>(String::class, autoRelease = false) {
            override fun onReadTyped(ctx: PipelineHandlerContext, msg: String) {
                received.add(msg)
            }
        }
        pipeline.addLast("typed", handler)
        pipeline.notifyRead("hello")
        assertEquals(listOf("hello"), received)
    }

    @Test
    fun `typed handler passes non-matching messages to next handler`() {
        val pipeline = createPipeline()
        val stringReceived = mutableListOf<String>()
        val intReceived = mutableListOf<Int>()

        val stringHandler = object : TypedInboundHandler<String>(String::class, autoRelease = false) {
            override fun onReadTyped(ctx: PipelineHandlerContext, msg: String) {
                stringReceived.add(msg)
            }
        }
        val intHandler = object : TypedInboundHandler<Int>(Int::class, autoRelease = false) {
            override fun onReadTyped(ctx: PipelineHandlerContext, msg: Int) {
                intReceived.add(msg)
            }
        }
        pipeline.addLast("strings", stringHandler)
        pipeline.addLast("ints", intHandler)

        pipeline.notifyRead("hello")
        pipeline.notifyRead(42)

        assertEquals(listOf("hello"), stringReceived)
        assertEquals(listOf(42), intReceived)
    }

    // --- acceptedType ---

    @Test
    fun `typed handler sets acceptedType from constructor`() {
        val handler = object : TypedInboundHandler<String>(String::class) {
            override fun onReadTyped(ctx: PipelineHandlerContext, msg: String) {}
        }
        assertEquals(String::class, handler.acceptedType)
    }

    // --- reified factory ---

    @Test
    fun `typedHandler factory creates handler with correct type`() {
        val received = mutableListOf<String>()
        val handler = typedHandler<String> { _, msg -> received.add(msg) }

        val pipeline = createPipeline()
        pipeline.addLast("h", handler)
        pipeline.notifyRead("test")
        assertEquals(listOf("test"), received)
    }

    @Test
    fun `typedHandler factory sets acceptedType`() {
        val handler = typedHandler<Int> { _, _ -> }
        assertEquals(Int::class, handler.acceptedType)
    }

    // --- autoRelease + propagate tracking ---

    private class TrackableMessage {
        var released = false
    }

    @Test
    fun `autoRelease does not release when message is propagated`() {
        val pipeline = createPipeline()
        var propagated = false

        val handler = object : TypedInboundHandler<String>(String::class, autoRelease = true) {
            override fun onReadTyped(ctx: PipelineHandlerContext, msg: String) {
                // Propagate to next handler → autoRelease should skip
                ctx.propagateRead(msg)
                propagated = true
            }
        }
        val receiver = object : InboundHandler {
            var received: Any? = null
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                received = msg
            }
        }
        pipeline.addLast("typed", handler)
        pipeline.addLast("receiver", receiver)

        pipeline.notifyRead("hello")
        assertTrue(propagated)
        assertEquals("hello", receiver.received)
    }

    // --- Exception handling ---

    @Test
    fun `exception in onReadTyped propagates as error`() {
        val pipeline = createPipeline()
        val errors = mutableListOf<String>()

        val failing = object : TypedInboundHandler<String>(String::class, autoRelease = false) {
            override fun onReadTyped(ctx: PipelineHandlerContext, msg: String) {
                throw RuntimeException("handler error")
            }
        }
        val errorCatcher = object : InboundHandler {
            override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                errors.add(cause.message ?: "")
            }
        }
        pipeline.addLast("failing", failing)
        pipeline.addLast("errors", errorCatcher)

        pipeline.notifyRead("trigger")
        assertEquals(listOf("handler error"), errors)
    }
}
