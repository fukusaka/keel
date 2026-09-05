package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.internal.DefaultPipeline
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pipeline's end of life and the frame it is kept out of.
 *
 * A channel's life ends when its close walk completes, whoever started it:
 * the ending is delivered if it was not, and every handler is removed —
 * `handlerRemoved` once each, as the last thing it hears. None of that runs
 * inside a handler's callback: a close a handler starts from its own `onRead`
 * is released at once, but the ending and the removals wait for that read to
 * return, so the handlers below are neither swept out from under the sweep
 * nor freed while their own frame still uses them.
 */
class PipelineLifeStateTest {

    private class Fixture(deferDrain: Boolean = false, val tracker: TrackingAllocator = TrackingAllocator()) {
        val queue = QueueingDispatcher()

        // The pipeline captures the transport's dispatcher when the channel is
        // built, so a deferred drain has to be chosen before that.
        val transport = TestIoTransport(tracker).apply { if (deferDrain) dispatcher = queue }
        val log = mutableListOf<String>()
        val channel: PipelinedChannel = object : AbstractPipelinedChannel(transport, PrintLogger("life")) {}
        val pipeline: Pipeline get() = channel.pipeline
        val life: DefaultPipeline.Life get() = (channel.pipeline as DefaultPipeline).life

        fun recorder(name: String): Recorder = Recorder(name, log)

        fun read(): IoBuf = tracker.allocate(8).also { it.writerIndex = 4 }
    }

    /** Records every event under its name and passes everything on. */
    private open class Recorder(val name: String, val log: MutableList<String>) : DuplexHandler {
        override fun handlerAdded(ctx: PipelineHandlerContext) {
            log.add("$name:added")
        }

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

        override fun handlerRemoved(ctx: PipelineHandlerContext) {
            log.add("$name:removed")
        }
    }

    @Test
    fun `a close a handler starts from its own read ends the life after the read returns`() {
        val f = Fixture()
        var lifeInsideTheRead: DefaultPipeline.Life? = null
        var belowStillThere = false
        val initiator = object : Recorder("m", f.log) {
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                log.add("m:read")
                ctx.propagateClose()
                lifeInsideTheRead = f.life
                belowStillThere = ctx.pipeline.get("b") != null
                log.add("m:read:end")
            }
        }
        f.pipeline.addLast("a", f.recorder("a"))
        f.pipeline.addLast("m", initiator)
        f.pipeline.addLast("b", f.recorder("b"))
        f.log.clear()

        f.pipeline.notifyRead("payload")

        assertEquals(
            listOf(
                "a:read", "m:read", "a:close", "m:read:end",
                "a:inactive", "m:inactive", "b:inactive",
                "b:removed", "m:removed", "a:removed",
            ),
            f.log,
            "the head side hears the close inside the read; the ending and the removals come after it",
        )
        assertEquals(DefaultPipeline.Life.TERMINATE_OWED, lifeInsideTheRead, "owed to the read's return")
        assertTrue(belowStillThere, "nothing was removed while the read was on the stack")
        assertEquals(DefaultPipeline.Life.DESTROYED, f.life)
        assertFalse(f.transport.isOpen, "the descriptor is released at once, inside the read")
        assertTrue(f.pipeline.isEmpty)
    }

    @Test
    fun `a channel closed from inside the ending still delivers the ending to the handlers below`() {
        val f = Fixture()
        val closer = object : Recorder("a", f.log) {
            override fun onInactive(ctx: PipelineHandlerContext) {
                log.add("a:inactive")
                ctx.channel.close()
                ctx.propagateInactive()
            }
        }
        f.pipeline.addLast("a", closer)
        f.pipeline.addLast("b", f.recorder("b"))
        f.log.clear()

        f.channel.close()

        assertEquals(
            listOf("a:inactive", "b:close", "a:close", "b:inactive", "b:removed", "a:removed"),
            f.log,
            "the re-entered close runs the walk inside the sweep, and the sweep still reaches b",
        )
    }

    @Test
    fun `a close of the channel inside the frame of a handler-initiated close reaches the tail side`() {
        val f = Fixture()
        val initiator = object : Recorder("m", f.log) {
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                log.add("m:read")
                ctx.propagateClose()
                ctx.channel.close()
            }
        }
        f.pipeline.addLast("a", f.recorder("a"))
        f.pipeline.addLast("m", initiator)
        f.pipeline.addLast("b", f.recorder("b"))
        f.log.clear()

        f.pipeline.notifyRead("payload")

        assertEquals(
            listOf(
                "a:read", "m:read", "a:close",
                "a:inactive", "m:inactive", "b:inactive",
                "b:close", "m:close",
                "b:removed", "m:removed", "a:removed",
            ),
            f.log,
            "the channel's close, asked for before the removals, walks the tail side and skips a",
        )
    }

    @Test
    fun `a close asked for while a handler-initiated walk runs is served from the tail when it completes`() {
        val f = Fixture()
        val initiator = object : Recorder("m", f.log) {
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                log.add("m:read")
                ctx.propagateClose()
            }
        }
        val closingBack = object : Recorder("a", f.log) {
            override fun onClose(ctx: PipelineHandlerContext) {
                log.add("a:close")
                ctx.channel.close()
                ctx.propagateClose()
            }
        }
        f.pipeline.addLast("a", closingBack)
        f.pipeline.addLast("m", initiator)
        f.pipeline.addLast("b", f.recorder("b"))
        f.log.clear()

        f.pipeline.notifyRead("payload")

        assertEquals(
            listOf(
                "a:read", "m:read", "a:close",
                "a:inactive", "m:inactive", "b:inactive",
                "b:close", "m:close",
                "b:removed", "m:removed", "a:removed",
            ),
            f.log,
            "the owed walk runs at the first walk's end, before the end of life removes the tail side",
        )
    }

    @Test
    fun `handlerRemoved runs once for a handler its neighbour removes from its own removal`() {
        val f = Fixture()
        val remover = object : Recorder("b", f.log) {
            override fun handlerRemoved(ctx: PipelineHandlerContext) {
                log.add("b:removed")
                ctx.pipeline.remove("a")
            }
        }
        f.pipeline.addLast("a", f.recorder("a"))
        f.pipeline.addLast("b", remover)
        f.log.clear()

        f.channel.close()

        assertEquals(1, f.log.count { it == "a:removed" }, "removed by b, not again by the end of life: ${f.log}")
        assertEquals(1, f.log.count { it == "b:removed" })
        assertTrue(f.pipeline.isEmpty)
    }

    @Test
    fun `a handler added after the end of life is served a whole lifecycle and the pipeline stays empty`() {
        val f = Fixture()
        f.channel.close()
        f.log.clear()

        f.pipeline.addLast("late", f.recorder("late"))
        f.pipeline.addFirst(
            "outbound",
            object : OutboundHandler {
                override fun onWrite(ctx: PipelineHandlerContext, msg: Any) = ctx.propagateWrite(msg)
            },
        )

        assertEquals(listOf("late:added", "late:inactive", "late:removed"), f.log)
        assertNull(f.pipeline.get("late"))
        assertTrue(f.pipeline.isEmpty)
        assertEquals(DefaultPipeline.Life.DESTROYED, f.life)
    }

    @Test
    fun `a close from a read the channel's own close replays delivers the reads before it and releases the rest`() {
        val f = Fixture(deferDrain = true)
        f.pipeline.notifyRead(f.read())
        f.pipeline.notifyRead(f.read())
        f.pipeline.notifyRead(f.read())
        val closer = object : Recorder("h", f.log) {
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                log.add("h:read")
                (msg as IoBuf).release()
                ctx.channel.close()
            }
        }
        f.pipeline.addLast("h", closer)
        assertEquals(3, f.tracker.outstandingCount, "premise: the reads wait for the queued drain")

        f.channel.close()

        assertEquals(
            listOf("h:added", "h:active", "h:read", "h:inactive", "h:close", "h:removed"),
            f.log,
            "the first replayed read closes the channel; the two behind it are released, not delivered",
        )
        assertEquals(0, f.tracker.outstandingCount)
        f.queue.runQueued()
        assertEquals(0, f.tracker.outstandingCount, "the queued drain finds nothing left")
        assertEquals(DefaultPipeline.Life.DESTROYED, f.life)
    }

    @Test
    fun `a close a handler starts from a replayed read releases the rest of the journal and delivers no data after it`() {
        val f = Fixture(deferDrain = true)
        f.pipeline.notifyRead(f.read())
        f.pipeline.notifyRead(f.read())
        f.pipeline.notifyReadComplete()
        f.pipeline.notifyFlushComplete()
        val closer = object : Recorder("h", f.log) {
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                log.add("h:read")
                (msg as IoBuf).release()
                ctx.channel.close()
            }

            override fun onReadComplete(ctx: PipelineHandlerContext) {
                log.add("h:readComplete")
                ctx.propagateReadComplete()
            }

            override fun onFlushComplete(ctx: PipelineHandlerContext) {
                log.add("h:flushComplete")
                ctx.propagateFlushComplete()
            }
        }
        f.pipeline.addLast("h", closer)
        assertEquals(2, f.tracker.outstandingCount, "premise: the reads wait for the queued drain")

        f.queue.runQueued()

        assertEquals(
            listOf("h:added", "h:active", "h:read", "h:inactive", "h:close", "h:removed"),
            f.log,
            "the first replayed read closes the channel; the handler is removed before the rest of the journal " +
                "(the second read, the boundary, the flush completion), and none of it reaches it",
        )
        assertEquals(0, f.tracker.outstandingCount, "the read behind the close is released")
        assertEquals(DefaultPipeline.Life.DESTROYED, f.life)
    }

    @Test
    fun `an ending a handler raises from a read does not make the real ending arrive twice below it`() {
        val f = Fixture()
        val raiser = object : Recorder("t", f.log) {
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                log.add("t:read")
                ctx.propagateInactive()
            }
        }
        f.pipeline.addLast("t", raiser)
        f.pipeline.addLast("b", f.recorder("b"))
        f.log.clear()

        f.pipeline.notifyRead("close_notify")
        f.pipeline.notifyInactive()

        assertEquals(listOf("t:read", "b:inactive", "t:inactive"), f.log)
        assertEquals(1, f.log.count { it == "b:inactive" }, "b's own state stops the second delivery")
    }

    @Test
    fun `a handler that consumes the close is not asked again and is still removed`() {
        val f = Fixture()
        val consumer = object : Recorder("c", f.log) {
            override fun onClose(ctx: PipelineHandlerContext) {
                log.add("c:close")
            }
        }
        f.pipeline.addLast("a", f.recorder("a"))
        f.pipeline.addLast("c", consumer)
        f.log.clear()

        f.channel.close()
        f.channel.close()

        assertEquals(listOf("a:inactive", "c:inactive", "c:close", "c:removed", "a:removed"), f.log)
        assertFalse(f.transport.isOpen, "the descriptor does not depend on the walk reaching the head")
    }

    @Test
    fun `the drain owed to an inline dispatcher waits for the outermost handler frame`() {
        val f = Fixture()
        val installer = object : OutboundHandler {
            override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
                f.log.add("w:write")
                ctx.pipeline.addLast("r", f.recorder("r"))
                f.log.add("w:write:end")
                ctx.propagateWrite(msg)
            }
        }
        f.pipeline.addLast("w", installer)

        f.pipeline.requestWrite(f.read())

        assertEquals(
            listOf("w:write", "r:added", "w:write:end", "r:active"),
            f.log,
            "the activation the drain delivers is not run inside the write that installed the handler",
        )
        f.channel.close()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a handler that closes back from inside an in-place walk does not walk again or hold the release`() {
        val f = Fixture()
        f.transport.owningContext = false
        f.transport.owningContextAlive = false
        val closer = object : Recorder("h", f.log) {
            override fun onClose(ctx: PipelineHandlerContext) {
                log.add("h:close")
                ctx.channel.close()
                ctx.propagateClose()
            }
        }
        f.pipeline.addLast("h", closer)
        f.log.clear()

        f.channel.close()

        assertEquals(listOf("h:inactive", "h:close", "h:removed"), f.log)
        assertFalse(f.transport.isOpen)
        assertEquals(DefaultPipeline.Life.DESTROYED, f.life)
    }

    @Test
    fun `a head close that throws is retried at the walk's end and the life still ends`() {
        val tracker = TrackingAllocator()
        val transport = object : TestIoTransport(tracker) {
            var threw = false

            override fun close() {
                if (!threw) {
                    threw = true
                    throw IllegalStateException("first close refused")
                }
                super.close()
            }
        }
        val log = mutableListOf<String>()
        val channel = object : AbstractPipelinedChannel(transport, PrintLogger("life")) {}
        channel.pipeline.addLast("h", Recorder("h", log))
        log.clear()

        channel.close()

        assertEquals(listOf("h:inactive", "h:close", "h:error", "h:removed"), log)
        assertFalse(transport.isOpen, "the walk's end closed what the head hop could not")
        assertTrue((channel.pipeline as DefaultPipeline).life == DefaultPipeline.Life.DESTROYED)
    }

    @Test
    fun `nothing starts a sweep or a walk once the pipeline is being destroyed`() {
        val f = Fixture()
        val stubborn = object : Recorder("h", f.log) {
            override fun handlerRemoved(ctx: PipelineHandlerContext) {
                log.add("h:removed")
                ctx.channel.close()
                ctx.pipeline.notifyInactive()
                ctx.pipeline.notifyActive()
                ctx.pipeline.requestClose()
            }
        }
        f.pipeline.addLast("h", stubborn)
        f.pipeline.addLast("b", f.recorder("b"))
        f.log.clear()

        f.channel.close()

        assertEquals(listOf("h:inactive", "b:inactive", "b:close", "h:close", "b:removed", "h:removed"), f.log)
        assertEquals(DefaultPipeline.Life.DESTROYED, f.life)
    }

    @Test
    fun `a handler removed by one below it from inside its own read is told so synchronously`() {
        val f = Fixture()
        val remover = object : Recorder("a", f.log) {
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                log.add("a:read")
                ctx.pipeline.remove("t")
                log.add("a:read:end")
            }
        }
        f.pipeline.addLast("t", f.recorder("t"))
        f.pipeline.addLast("a", remover)
        f.log.clear()

        f.pipeline.notifyRead("payload")

        assertEquals(listOf("t:read", "a:read", "t:removed", "a:read:end"), f.log)
        assertNull(f.pipeline.get("t"))
        assertNotNull(f.pipeline.get("a"))
    }

    @Test
    fun `a close from off the loop delivers neither the journalled reads nor the activation after the descriptor is gone`() {
        val f = Fixture(deferDrain = true)
        f.pipeline.notifyRead(f.read())
        f.pipeline.notifyRead(f.read())
        f.pipeline.notifyReadComplete()
        f.pipeline.notifyFlushComplete()
        val handler = object : Recorder("h", f.log) {
            override fun onReadComplete(ctx: PipelineHandlerContext) {
                log.add("h:readComplete")
                ctx.propagateReadComplete()
            }

            override fun onFlushComplete(ctx: PipelineHandlerContext) {
                log.add("h:flushComplete")
                ctx.propagateFlushComplete()
            }
        }
        f.pipeline.addLast("h", handler)
        f.transport.owningContext = false
        f.queue.onRun = { f.transport.owningContext = true }

        f.channel.close()
        assertFalse(f.transport.isOpen, "released before the hand-off")
        f.queue.runQueued()

        assertEquals(
            listOf("h:added", "h:inactive", "h:close", "h:removed"),
            f.log,
            "the drain the hand-off runs first finds the descriptor gone: no activation, no read, no boundary, " +
                "no flush completion — only the ending",
        )
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a handler installed below a running sweep that throws on its replay does not hide the sweep from the handlers below`() {
        val f = Fixture(deferDrain = true)
        val thrower = object : Recorder("t", f.log) {
            override fun onActive(ctx: PipelineHandlerContext) {
                log.add("t:active")
                throw IllegalStateException("refused")
            }
        }
        val installer = object : Recorder("a", f.log) {
            override fun onActive(ctx: PipelineHandlerContext) {
                log.add("a:active")
                ctx.pipeline.addAfter("a", "t", thrower)
                ctx.propagateActive()
            }
        }
        f.pipeline.addLast("a", installer)
        f.pipeline.addLast("b", f.recorder("b"))
        f.log.clear()

        f.queue.runQueued()

        // The sweep, not a replay, delivers to the handler installed below
        // its position: a throw there is propagated on the thrower's behalf
        // and reaches b, where a replay's throw would only have been logged
        // and the sweep would then have stopped at the thrower's own state.
        assertEquals(listOf("a:active", "t:added", "t:active", "b:error", "b:active"), f.log)
    }

    @Test
    fun `a journalled reason is still delivered by the drain after the transport closed itself and before the ending`() {
        val f = Fixture(deferDrain = true)
        f.pipeline.addLast("h", f.recorder("h"))
        // What a refused send looks like from the pipeline: the reason, then
        // the transport closing itself, then its ending — all journalled
        // because the drain is still queued, and the head stayed quiet about
        // the reason because the drain was going to hand it over.
        f.pipeline.notifyError(IllegalStateException("refused"))
        f.transport.close()
        f.pipeline.notifyInactive()

        f.channel.close()

        assertEquals(
            listOf("h:added", "h:error", "h:inactive", "h:close", "h:removed"),
            f.log,
            "the reason is not data: the descriptor being gone does not drop it, only the ending's delivery would",
        )
    }

    @Test
    fun `a handler added after the loop stopped gives up a drain that was already scheduled`() {
        val f = Fixture(deferDrain = true)
        f.pipeline.addLast("a", f.recorder("a"))
        f.pipeline.notifyRead(f.read())
        assertEquals(1, f.tracker.outstandingCount, "premise: the read waits for the queued drain")
        f.transport.owningContext = false
        f.transport.owningContextAlive = false

        f.pipeline.addLast("b", f.recorder("b"))

        assertEquals(0, f.tracker.outstandingCount, "the drain that was queued is never going to run")
        assertEquals(
            listOf("a:added", "b:added", "a:active", "b:active"),
            f.log,
            "and the lifecycle is delivered in place",
        )
    }

    @Test
    fun `a transport that throws on close from off the loop does not keep the channel from ending`() {
        val tracker = TrackingAllocator()
        val queue = QueueingDispatcher()
        val transport = object : TestIoTransport(tracker) {
            var threw = false

            override fun close() {
                if (!threw) {
                    threw = true
                    throw IllegalStateException("first close refused")
                }
                super.close()
            }
        }
        transport.dispatcher = queue
        val log = mutableListOf<String>()
        val channel = object : AbstractPipelinedChannel(transport, PrintLogger("life")) {}
        channel.pipeline.addLast("h", Recorder("h", log))
        log.clear()
        transport.owningContext = false
        queue.onRun = { transport.owningContext = true }

        channel.close()
        queue.runQueued()

        // The refused release left the transport open, so the queued drain
        // still activates; the hand-off then ends the connection and the
        // walk's end releases what the caller's close could not.
        assertEquals(listOf("h:active", "h:inactive", "h:close", "h:removed"), log)
        assertFalse(transport.isOpen)
        assertEquals(DefaultPipeline.Life.DESTROYED, (channel.pipeline as DefaultPipeline).life)
    }

    @Test
    fun `a handler inserted ahead of a running writability sweep does not stop the sweep`() {
        val f = Fixture()
        val values = mutableListOf<String>()
        val inserter = object : InboundHandler {
            override fun onWritabilityChanged(ctx: PipelineHandlerContext, isWritable: Boolean) {
                values.add("w:$isWritable")
                ctx.pipeline.addAfter("w", "x", f.recorder("x"))
                ctx.propagateWritabilityChanged(isWritable)
            }
        }
        val below = object : InboundHandler {
            override fun onWritabilityChanged(ctx: PipelineHandlerContext, isWritable: Boolean) {
                values.add("y:$isWritable")
            }
        }
        f.pipeline.addLast("w", inserter)
        f.pipeline.addLast("y", below)
        f.log.clear()

        f.pipeline.notifyWritabilityChanged(false)

        // The sweep delivers to x on its way, not a replay ahead of it whose
        // record would make the sweep stop there and keep the change from y.
        assertEquals(listOf("w:false", "y:false"), values)
        assertEquals(
            listOf("x:added", "x:active"),
            f.log,
            "x is caught up on the activation only; the sweep brings the value",
        )
    }

    @Test
    fun `a handler removed after the loop stopped gives up a drain that was already scheduled`() {
        val f = Fixture(deferDrain = true)
        f.pipeline.addLast("a", f.recorder("a"))
        f.pipeline.addLast("b", f.recorder("b"))
        f.pipeline.notifyRead(f.read())
        f.transport.owningContext = false
        f.transport.owningContextAlive = false

        f.pipeline.remove("b")

        assertEquals(0, f.tracker.outstandingCount, "the drain that was queued is never going to run")
    }

    @Test
    fun `a read arriving on a destroyed pipeline is released`() {
        val f = Fixture()
        f.pipeline.addLast("h", f.recorder("h"))
        f.channel.close()
        f.log.clear()

        f.pipeline.notifyRead(f.read())

        assertEquals(emptyList<String>(), f.log)
        assertEquals(0, f.tracker.outstandingCount)
    }
}
