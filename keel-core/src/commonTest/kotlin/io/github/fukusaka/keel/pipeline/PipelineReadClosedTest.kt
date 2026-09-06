package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Every case here parks a reader or waits on a dispatcher hop, so each is
 * bounded on the wall clock rather than on the test scheduler's virtual time.
 */
private fun readClosedTest(body: suspend TestScope.() -> Unit) = runTest(timeout = 15.seconds, testBody = body)

/**
 * The peer's end of file as its own event, apart from the ending.
 *
 * A peer that closes its side for writing has finished sending, and nothing
 * else: the connection is open and writable, and what the peer sent before
 * its FIN is still the reader's. The pipeline hears it as `onReadClosed`,
 * once, between the activation and the ending; a Pipeline-mode channel then
 * closes itself, a Coroutine-mode reader drains what was queued and gets
 * `-1`. The ending is a different fact — the connection is over — and comes
 * from a close, the channel's or the transport's own.
 */
/**
 * A transport that tells the peer's end of file apart from the end,
 * the way an engine reports once it has been taught the difference.
 * The base still answers for one that has not, and
 * [AbstractPipelinedChannelTest] is where that wiring is pinned.
 */
private open class SplitTestIoTransport(tracker: TrackingAllocator) : TestIoTransport(tracker) {
    override val reportsEveryEndAsReadClosed: Boolean get() = false
}

private class Fixture(deferDrain: Boolean = false, val tracker: TrackingAllocator = TrackingAllocator()) {
    val queue = QueueingDispatcher()
    val transport = SplitTestIoTransport(tracker).apply { if (deferDrain) dispatcher = queue }
    val log = mutableListOf<String>()
    val channel: PipelinedChannel = object : AbstractPipelinedChannel(transport, PrintLogger("read-closed")) {}
    val pipeline: Pipeline get() = channel.pipeline

    fun recorder(name: String): Recorder = Recorder(name, log)

    fun bytes(vararg values: Byte): IoBuf = tracker.allocate(8).also { buf -> for (v in values) buf.writeByte(v) }

    fun peerFin() {
        transport.onReadClosed?.invoke()
    }

    fun transportEnded() {
        transport.onClosed?.invoke()
    }
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

    override fun onReadClosed(ctx: PipelineHandlerContext) {
        log.add("$name:readClosed")
        ctx.propagateReadClosed()
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

class PipelineReadClosedTest {

    // --- Coroutine mode: the peer's last bytes are the reader's ---

    @Test
    fun `a peer that sends and then closes leaves its bytes for the reader`() = readClosedTest {
        // The ordinary request/response client: the peer answers and closes,
        // both in the same wake, before the caller's first read. The first
        // read returns the answer; only the read after it says EOF.
        val f = Fixture()
        f.transport.onRead?.invoke(f.bytes(1, 2, 3, 4))
        f.peerFin()

        val dst = f.tracker.allocate(16)
        assertEquals(4, f.channel.read(dst), "the bytes sent before the FIN")
        assertEquals(1, dst.readByte())
        assertEquals(-1, f.channel.read(dst), "then EOF")
        assertTrue(f.channel.isOpen, "a Coroutine-mode channel is the caller's to close")
        dst.release()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a reader parked before the peer's bytes and FIN arrive is handed the bytes`() = readClosedTest {
        val f = Fixture()
        val dst = f.tracker.allocate(16)
        val reader = async(start = CoroutineStart.UNDISPATCHED) { f.channel.read(dst) }
        assertFalse(reader.isCompleted, "premise: the reader is parked")

        f.transport.onRead?.invoke(f.bytes(9, 8))
        f.peerFin()

        assertEquals(2, reader.await())
        assertEquals(-1, f.channel.read(dst))
        dst.release()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `after the peer's FIN the connection is still writable and the caller's close ends it once`() = readClosedTest {
        val f = Fixture()
        f.pipeline.addLast("h", f.recorder("h"))
        f.channel.ensureBridge()
        f.peerFin()
        assertTrue(f.channel.isOpen)

        // The half-closed peer can still be answered.
        f.channel.write(f.bytes(7))
        f.channel.flush()
        assertEquals(1, f.transport.written.size, "the answer reached the transport")
        assertTrue(f.transport.flushed)

        f.channel.close()
        assertEquals(
            listOf("h:added", "h:active", "h:readClosed", "h:inactive", "h:close", "h:removed"),
            f.log,
            "the FIN is the read side; the ending comes with the caller's close, once",
        )
        assertFalse(f.channel.isOpen)
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `in Pipeline mode the peer's FIN is followed by the channel's own close`() {
        val f = Fixture()
        f.pipeline.addLast("h", f.recorder("h"))

        f.peerFin()

        assertEquals(
            listOf("h:added", "h:active", "h:readClosed", "h:inactive", "h:close", "h:removed"),
            f.log,
            "read-closed first, then the ending and the close walk from the channel's close, then the removal",
        )
        assertFalse(f.transport.isOpen, "keel owns the connection in Pipeline mode and releases it")
    }

    // --- The transport's own end: no FIN, just the ending ---

    @Test
    fun `an end of file held for a chain is delivered by the discard when the loop has stopped`() = readClosedTest {
        // The FIN arrives with nothing installed, so it is held. Then the owning
        // context stops, and the first handler added is what notices that the drain
        // which would have delivered it is never going to run. The discard is the
        // only thing left that can: after it the event is no longer merely observed,
        // so no later drain sweeps it.
        val f = Fixture()
        f.peerFin()
        f.transport.owningContextAlive = false

        f.pipeline.addLast("late", f.recorder("late"))

        assertTrue(f.log.contains("late:readClosed"), "the discard delivers the end of file it was holding")
        assertFalse(f.channel.isOpen, "and the chain that heard it had made the connection keel's")
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a close a handler walked to the head is this side's`() = readClosedTest {
        // Nobody asked the pipeline for this close: a handler starts the walk from
        // its own callback and it runs all the way to the head, which is where the
        // transport is released. A report arriving afterwards is it catching up.
        val f = Fixture()
        f.channel.ensureBridge()
        f.pipeline.addLast(
            "starter",
            object : DuplexHandler {
                override fun onReadClosed(ctx: PipelineHandlerContext) {
                    ctx.propagateClose()
                }
            },
        )

        f.peerFin()
        f.transportEnded()

        assertFalse(f.channel.endedByTransport, "the walk that reached the head was this side's close")
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a handler installed below a running sweep hears the end of file from the sweep`() = readClosedTest {
        // A handler joining below a sweep that has not propagated is the sweep's to
        // reach, not the replay's: a replay holds its propagation back, so the
        // handlers past it would hear the event from neither.
        val f = Fixture()
        f.channel.ensureBridge()
        f.pipeline.addLast("tailward", f.recorder("tailward"))
        f.pipeline.addBefore(
            "tailward",
            "installer",
            object : InboundHandler {
                override fun onReadClosed(ctx: PipelineHandlerContext) {
                    ctx.pipeline.addAfter("installer", "joiner", f.recorder("joiner"))
                    ctx.propagateReadClosed()
                }
            },
        )

        f.peerFin()

        assertTrue(f.log.contains("joiner:readClosed"), "the handler installed mid-sweep hears it")
        assertTrue(f.log.contains("tailward:readClosed"), "and so does the one already below it")
        f.channel.close()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a handler that throws on the replayed end of file keeps the throw to itself`() = readClosedTest {
        // A replay is to the joining handler alone — the handlers below heard the
        // event when it swept the chain — so its throw is logged and nothing is
        // carried on for it. In the sweep both are: the error travels and the event
        // still reaches the handlers past the thrower.
        val f = Fixture()
        f.channel.ensureBridge()
        f.pipeline.addLast("below", f.recorder("below"))
        f.peerFin()
        assertTrue(f.log.contains("below:readClosed"), "premise: the chain heard it in the sweep")

        f.pipeline.addFirst(
            "thrower",
            object : InboundHandler {
                override fun onReadClosed(ctx: PipelineHandlerContext): Unit = throw IllegalStateException("boom")
            },
        )

        assertFalse(f.log.contains("below:error"), "a replay's throw is logged, not propagated as an error")
        assertTrue(f.channel.isOpen, "and a channel with a caller of its own is still the caller's")
        f.channel.close()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a handler below one that closed on the end of file hears the ending and not the end of file`() = readClosedTest {
        val f = Fixture()
        f.pipeline.addLast(
            "closer",
            object : Recorder("closer", f.log) {
                override fun onReadClosed(ctx: PipelineHandlerContext) {
                    log.add("$name:readClosed")
                    // The connection ends under the handlers below before the
                    // walk reaches them: what they are owed then is the
                    // ending, and the read side's end is no longer news.
                    ctx.channel.pipeline.notifyInactive()
                    ctx.propagateReadClosed()
                }
            },
        )
        f.pipeline.addLast("below", f.recorder("below"))

        f.peerFin()

        assertFalse(
            f.log.contains("below:readClosed"),
            "the end of file must not reach a handler the ending already reached: ${f.log}",
        )
        assertTrue(f.log.contains("below:inactive"), "premise: the close did reach it")
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `the bridge passes the peer's end of file to the handlers below it`() = readClosedTest {
        // The bridge answers its own caller and is not the end of the chain:
        // a handler installed after it hears the event too.
        val f = Fixture()
        f.channel.ensureBridge()
        f.pipeline.addLast("below", f.recorder("below"))

        f.peerFin()

        assertEquals(listOf("below:added", "below:active", "below:readClosed"), f.log)
        assertTrue(f.channel.isOpen, "a channel with a bridge is its caller's to close")
    }

    @Test
    fun `a handler installing a bridge while hearing the end of file does not answer for the report`() = readClosedTest {
        // Which mode the channel is in is the report's question. A handler
        // that installs a bridge from inside its own callback would otherwise
        // make a Pipeline-mode channel look like a caller's, and it would
        // keep its descriptor with nobody to release it.
        val f = Fixture()
        f.pipeline.addLast(
            "only",
            object : InboundHandler {
                override fun onReadClosed(ctx: PipelineHandlerContext) {
                    ctx.channel.ensureBridge()
                }
            },
        )

        f.peerFin()

        assertFalse(f.channel.isOpen, "the chain the report arrived to had no caller of its own")
    }

    @Test
    fun `a handler joining after the end of file was delivered is told the connection is over`() = readClosedTest {
        // The report found an empty chain, so nothing owned the connection
        // and nothing closed. A handler arriving afterwards makes it keel's,
        // and it is asked again: without that the handler hears the peer
        // finished, never hears the ending, is never removed, and the
        // descriptor stays.
        val f = Fixture()
        f.pipeline.addLast("h0", f.recorder("h0"))
        f.pipeline.remove("h0")
        f.peerFin()
        f.log.clear()

        f.pipeline.addLast("h1", f.recorder("h1"))

        assertEquals(
            listOf("h1:added", "h1:active", "h1:readClosed", "h1:inactive", "h1:close", "h1:removed"),
            f.log,
        )
        assertFalse(f.channel.isOpen, "and the descriptor goes with it")
    }

    @Test
    fun `a handler that joins and then removes itself does not answer for the chain it joined`() = readClosedTest {
        // The replay asks who owns the connection before the handler hears
        // the event, the way the sweep does: a handler that leaves from
        // inside its own callback must not be able to answer for the chain
        // it was joining, or the descriptor it made keel's stays.
        val f = Fixture()
        f.pipeline.addLast("h0", f.recorder("h0"))
        f.pipeline.remove("h0")
        f.peerFin()

        f.pipeline.addLast(
            "late",
            object : InboundHandler {
                override fun onReadClosed(ctx: PipelineHandlerContext) {
                    ctx.pipeline.remove("late")
                }
            },
        )

        assertFalse(f.channel.isOpen, "the chain it joined had made the connection keel's")
    }

    @Test
    fun `a chain emptied while the end of file waits for its drain still releases the descriptor`() = readClosedTest {
        // The report arrived to a chain keel was driving, and the drain that
        // carries it runs a loop task later. Emptying the chain in between
        // hands the connection to nobody — the caller never had a bridge —
        // so the decision reads the chain the report arrived to as well.
        val f = Fixture(deferDrain = true)
        f.pipeline.addLast("h", f.recorder("h"))

        f.peerFin()
        f.pipeline.remove("h")
        f.queue.runQueued()

        assertFalse(f.channel.isOpen, "nothing was left that could release it")
    }

    @Test
    fun `a chain emptied of its handlers leaves the channel its caller's`() = readClosedTest {
        // Pipeline mode is handlers in the chain and no bridge among them. A
        // chain someone emptied is nobody's but its caller's: closing it on
        // the peer's end of file would refuse that caller's next read as a
        // misuse where it is owed the end of file.
        val f = Fixture()
        f.pipeline.addLast("h", f.recorder("h"))
        f.pipeline.remove("h")

        f.peerFin()

        assertTrue(f.channel.isOpen, "an empty chain is not Pipeline mode")
        val dst = f.tracker.allocate(8)
        assertEquals(-1, f.channel.read(dst), "and its caller is owed the end of file")
        dst.release()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a handler that empties the chain while hearing the end of file does not keep the descriptor`() = readClosedTest {
        // Which mode the channel is in is the report's question, not the
        // handler's answer to it. A one-shot handler that removes itself
        // from inside its own callback would otherwise leave a Pipeline-mode
        // channel — one with no caller of its own — holding its descriptor.
        val f = Fixture()
        f.pipeline.addLast(
            "only",
            object : InboundHandler {
                override fun onReadClosed(ctx: PipelineHandlerContext) {
                    ctx.pipeline.remove("only")
                }
            },
        )

        f.peerFin()

        assertFalse(f.channel.isOpen, "the chain it emptied had nobody else to release it")
    }

    @Test
    fun `a handler that releases the transport stops the end of file it was passing on`() = readClosedTest {
        // The descriptor is what the event is about. Once a handler has let
        // it go — a close from inside its own callback — the handlers below
        // are owed the ending, not a report that the peer merely finished.
        val f = Fixture()
        f.pipeline.addLast(
            "first",
            object : InboundHandler {
                override fun onReadClosed(ctx: PipelineHandlerContext) {
                    f.transport.close()
                    ctx.propagateReadClosed()
                }
            },
        )
        f.pipeline.addLast("second", f.recorder("second"))

        f.peerFin()

        assertEquals(
            listOf("second:added", "second:active", "second:inactive", "second:close", "second:removed"),
            f.log,
            "there is no connection left to answer on, so what reaches the handlers below is the ending",
        )
    }

    @Test
    fun `a handler added below one that already passed the event on is replayed to`() = readClosedTest {
        // Passing the event on records that the handler did, so a handler
        // installed below it afterwards is behind the sweep and is replayed
        // to instead. Without that record the sweep is thought to still be
        // coming, and the new handler hears nothing.
        val f = Fixture()
        f.pipeline.addLast(
            "first",
            object : InboundHandler {
                override fun onReadClosed(ctx: PipelineHandlerContext) {
                    ctx.propagateReadClosed()
                    f.pipeline.addLast("late", f.recorder("late"))
                }
            },
        )

        f.peerFin()

        assertEquals(
            listOf("late:added", "late:active", "late:readClosed", "late:inactive", "late:close", "late:removed"),
            f.log,
            "the replay reaches it, and the close this chain performs follows",
        )
    }

    @Test
    fun `a typed handler passes the event on from inside its own read`() = readClosedTest {
        // The context a typed handler is given inside `onReadTyped` wraps the
        // real one, and passing the event on has to reach through the wrapper.
        // That is the route a codec takes when its own protocol's close — a
        // TLS close_notify — is the peer's end of file to the chain below it.
        val f = Fixture()
        f.pipeline.addLast(
            "typed",
            object : TypedInboundHandler<IoBuf>(IoBuf::class) {
                override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
                    msg.release()
                    ctx.propagateReadClosed()
                }
            },
        )
        f.pipeline.addLast("below", f.recorder("below"))

        f.transport.onRead?.invoke(f.bytes(1))

        assertEquals(
            listOf("below:added", "below:active", "below:readClosed"),
            f.log,
            "the event reached through the wrapper",
        )
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a handler raising the read closed from the chain closes a Pipeline-mode channel`() = readClosedTest {
        // The report, whoever makes it: a TLS layer turning a close_notify
        // into the peer's end of file gets the close that follows delivery,
        // where passing the event on to the handlers below gets none.
        val raised = Fixture()
        raised.pipeline.addLast("h", raised.recorder("h"))
        raised.pipeline.notifyReadClosed()
        assertFalse(raised.channel.isOpen, "delivering it is what closes")

        val passedOn = Fixture()
        passedOn.pipeline.addLast(
            "h",
            object : InboundHandler {
                override fun onActive(ctx: PipelineHandlerContext) {
                    ctx.propagateReadClosed()
                }
            },
        )
        assertTrue(passedOn.channel.isOpen, "passing it on delivers nothing and closes nothing")
    }

    @Test
    fun `an end of file that finds the descriptor gone ends the chain and removes its handlers`() = readClosedTest {
        // There is no connection left to answer on, and a handler gives back
        // what it holds on being removed — so the whole ending is owed, not
        // the report that says the peer merely finished.
        val f = Fixture()
        f.pipeline.addLast("h", f.recorder("h"))
        f.transport.close()

        f.peerFin()

        assertEquals(listOf("h:added", "h:active", "h:inactive", "h:close", "h:removed"), f.log)
    }

    @Test
    fun `a bridge taken out of the chain by name leaves the channel its caller's`() = readClosedTest {
        // Whether the channel has a caller of its own is the field's answer
        // as much as the chain's: a bridge removed by name is still a caller
        // waiting to read, and closing here would refuse its next read.
        val f = Fixture()
        f.pipeline.addLast("h", f.recorder("h"))
        f.channel.ensureBridge()
        f.pipeline.remove(PipelinedChannel.SUSPEND_BRIDGE_NAME)

        f.peerFin()

        assertTrue(f.channel.isOpen, "the channel is left for the caller to close")
    }

    @Test
    fun `an end of file delivered from inside the bridge's own installation does not close the channel`() = readClosedTest {
        // The other reading. The add that installs the bridge drains the
        // journalled end of file to it, and the field is named only after
        // that add returns — so a delivery from inside it sees an empty
        // field on a channel that has a caller.
        val f = Fixture()
        f.peerFin()

        val dst = f.tracker.allocate(8)
        assertEquals(-1, f.channel.read(dst))
        dst.release()

        assertTrue(f.channel.isOpen, "the caller closes its own channel")
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a FIN and then the transport's end report the read side once and the ending once`() {
        val f = Fixture()
        f.pipeline.addLast("h", f.recorder("h"))
        f.channel.ensureBridge()

        f.peerFin()
        f.transportEnded()
        f.peerFin()

        assertEquals(
            listOf("h:added", "h:active", "h:readClosed", "h:inactive", "h:close", "h:removed"),
            f.log,
        )
    }

    // --- Once, between activation and ending ---

    @Test
    fun `a read-closed after the ending is not delivered and after the descriptor is gone is the ending`() {
        val afterEnding = Fixture()
        afterEnding.pipeline.addLast("h", afterEnding.recorder("h"))
        afterEnding.channel.ensureBridge()
        afterEnding.pipeline.notifyInactive()
        afterEnding.peerFin()
        assertEquals(listOf("h:added", "h:active", "h:inactive"), afterEnding.log)

        val afterRelease = Fixture()
        afterRelease.pipeline.addLast("h", afterRelease.recorder("h"))
        afterRelease.channel.ensureBridge()
        afterRelease.transport.close()
        afterRelease.pipeline.notifyReadClosed()
        assertEquals(
            listOf("h:added", "h:active", "h:inactive"),
            afterRelease.log,
            "the descriptor is gone: there is no connection left to answer on, so the chain is owed the ending",
        )
    }

    @Test
    fun `a handler that throws on the read-closed does not keep it from the handlers below`() {
        val f = Fixture()
        val thrower = object : Recorder("a", f.log) {
            override fun onReadClosed(ctx: PipelineHandlerContext) {
                log.add("a:readClosed")
                throw IllegalStateException("boom")
            }
        }
        f.pipeline.addLast("a", thrower)
        f.pipeline.addLast("b", f.recorder("b"))
        f.channel.ensureBridge()

        f.peerFin()

        assertEquals(
            listOf("a:added", "a:active", "b:added", "b:active", "a:readClosed", "b:error", "b:readClosed"),
            f.log,
        )
    }

    @Test
    fun `a handler that raises the read-closed from its own read does not make the transport's report arrive twice below`() {
        // The TLS handler on a close_notify: the handlers below hear the
        // peer's end of file from inside the read, and the transport's FIN
        // report afterwards finds them told.
        val f = Fixture()
        val raiser = object : Recorder("t", f.log) {
            override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                log.add("t:read")
                (msg as IoBuf).release()
                ctx.propagateReadClosed()
            }
        }
        f.pipeline.addLast("t", raiser)
        f.pipeline.addLast("b", f.recorder("b"))
        f.channel.ensureBridge()

        f.transport.onRead?.invoke(f.bytes(1))
        f.peerFin()

        assertEquals(
            listOf("t:added", "t:active", "b:added", "b:active", "t:read", "b:readClosed", "t:readClosed"),
            f.log,
            "below hears it from the raiser; the raiser hears it from the transport; nobody twice",
        )
    }

    // --- Late handlers and the journal ---

    @Test
    fun `a handler added after the peer's FIN hears it as a replay`() {
        val f = Fixture()
        f.channel.ensureBridge()
        f.peerFin()

        f.pipeline.addFirst("late", f.recorder("late"))

        assertEquals(
            listOf("late:added", "late:active", "late:readClosed"),
            f.log,
            "activation, then the read side's end; no ending",
        )
        assertTrue(f.channel.isOpen)
    }

    @Test
    fun `a FIN journalled before the first handler is delivered by the drain after the reads`() {
        val f = Fixture(deferDrain = true)
        f.transport.onRead?.invoke(f.bytes(1))
        f.transport.onReadComplete?.invoke()
        f.peerFin()
        f.pipeline.addLast("h", f.recorder("h"))
        assertEquals(1, f.tracker.outstandingCount, "premise: the read waits for the queued drain")

        f.queue.runQueued()

        assertEquals(
            listOf("h:added", "h:active", "h:read", "h:readClosed", "h:inactive", "h:close", "h:removed"),
            f.log,
            "the drain delivers the read, then the FIN, then the Pipeline-mode close ends the life",
        )
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a FIN journalled behind a flush completion a user event and a reason lets them reach the handler before the ending`() {
        // In Pipeline mode the FIN's delivery closes the channel, so it is
        // the last thing the drain delivers: what the journal held before
        // it — the answer to a flush, an event, the reason a failure was
        // reported with — is owed to the handlers before the end.
        val f = Fixture(deferDrain = true)
        f.transport.onFlushComplete?.invoke()
        f.pipeline.notifyUserEvent("evt")
        f.pipeline.notifyError(IllegalStateException("why"))
        f.peerFin()
        val h = object : Recorder("h", f.log) {
            override fun onFlushComplete(ctx: PipelineHandlerContext) {
                log.add("h:flushComplete")
                ctx.propagateFlushComplete()
            }

            override fun onUserEvent(ctx: PipelineHandlerContext, event: Any) {
                log.add("h:event")
                ctx.propagateUserEvent(event)
            }
        }
        f.pipeline.addLast("h", h)

        f.queue.runQueued()

        assertEquals(
            listOf(
                "h:added", "h:active", "h:flushComplete", "h:event", "h:error",
                "h:readClosed", "h:inactive", "h:close", "h:removed",
            ),
            f.log,
        )
    }

    @Test
    fun `a chain with only outbound handlers is closed on the peer's FIN`() {
        // No inbound handler ever asks for the journal's drain, so a FIN
        // journalled for such a chain would wait forever; it is judged at
        // once, and keel owns the connection in Pipeline mode.
        val f = Fixture()
        val encoder = object : OutboundHandler {
            override fun onClose(ctx: PipelineHandlerContext) {
                f.log.add("o:close")
                ctx.propagateClose()
            }

            override fun handlerRemoved(ctx: PipelineHandlerContext) {
                f.log.add("o:removed")
            }
        }
        f.pipeline.addLast("o", encoder)

        f.peerFin()

        assertEquals(listOf("o:close", "o:removed"), f.log)
        assertFalse(f.transport.isOpen, "keel owns the connection: the FIN closes it")
    }

    @Test
    fun `a handler added after the end of life hears the ending and not the read side`() {
        val f = Fixture()
        f.pipeline.addLast("h", f.recorder("h"))
        f.peerFin()
        assertFalse(f.channel.isOpen, "premise: Pipeline mode closed on the FIN")

        f.pipeline.addLast("late", f.recorder("late"))

        assertEquals(
            listOf("late:added", "late:inactive", "late:removed"),
            f.log.filter { it.startsWith("late:") },
        )
    }
}

/**
 * What a channel remembers about how its connection ended, and what a caller
 * reading it is told.
 *
 * The mark separates a connection that ended under its caller from one this
 * side closed — the caller's own close, a close asked of the pipeline, one a
 * handler walked to the head or ended where it stands. A read after the first
 * is the end of file; after the second it is the misuse the base refuses.
 */
class PipelineEndRecordTest {

    @Test
    fun `a reader suspended at the watermark drains everything after the FIN and is resumed`() = readClosedTest {
        val f = Fixture()
        val bridge = f.channel.ensureBridge()
        f.transport.readEnabled = true
        val chunk = 16 * 1024
        repeat(5) { f.transport.onRead?.invoke(f.tracker.allocate(chunk).also { it.writerIndex = chunk }) }
        assertTrue(bridge.readSuspendedByWatermark, "premise: 80 KiB queued crosses the high watermark")
        assertEquals(1, f.transport.pauseReadsCount)

        f.peerFin()

        var total = 0
        val dst = f.tracker.allocate(chunk)
        while (true) {
            dst.clear()
            val n = f.channel.read(dst)
            if (n < 0) break
            total += n
        }
        dst.release()
        assertEquals(5 * chunk, total, "every byte the peer sent before its FIN")
        assertEquals(
            1,
            f.transport.resumeReadsCount,
            "nothing more will arrive, but resuming is what arms the read, and the arming starts the clock " +
                "that reclaims a connection its caller never closes",
        )
        assertFalse(bridge.readSuspendedByWatermark)
        assertEquals(0, f.tracker.outstandingCount)
    }

    // --- Pipeline mode: the FIN ends the connection, the channel closes ---

    @Test
    fun `a connection the transport ended is an ending without a read-closed in either mode`() = readClosedTest {
        val pipelineMode = Fixture()
        pipelineMode.pipeline.addLast("h", pipelineMode.recorder("h"))
        pipelineMode.transportEnded()
        assertEquals(
            listOf("h:added", "h:active", "h:inactive", "h:close", "h:removed"),
            pipelineMode.log,
            "a reset is the end, not the peer finishing",
        )
        assertFalse(pipelineMode.transport.isOpen)

        val coroutineMode = Fixture()
        coroutineMode.channel.ensureBridge()
        coroutineMode.transport.onRead?.invoke(coroutineMode.bytes(1))
        coroutineMode.transportEnded()
        assertEquals(0, coroutineMode.tracker.outstandingCount, "what was queued is released: nobody can be handed it")
        assertFalse(
            coroutineMode.channel.isOpen,
            "the channel closes in Coroutine mode too — there is nothing left to answer",
        )
        val dst = coroutineMode.tracker.allocate(8)
        assertEquals(
            -1,
            coroutineMode.channel.read(dst),
            "a reader away for the end is told what the parked one was — nothing more to read — through the channel itself",
        )
        dst.release()
    }

    @Test
    fun `the end is remembered before the close so a reader arriving inside it reads the end of file`() = readClosedTest {
        // A reader that turns up while the transport's end is being processed
        // — here from the handler removal the close runs — must find the
        // channel already marked as ended by the transport; marked after the
        // close, it would be refused as a misuse instead.
        val f = Fixture()
        val dst = f.tracker.allocate(8)
        var arrived: Deferred<Int>? = null
        val scope = this
        f.pipeline.addLast(
            "h",
            object : Recorder("h", f.log) {
                override fun handlerRemoved(ctx: PipelineHandlerContext) {
                    arrived = scope.async(start = CoroutineStart.UNDISPATCHED) { f.channel.read(dst) }
                    super.handlerRemoved(ctx)
                }
            },
        )
        f.channel.ensureBridge()

        f.transportEnded()

        assertEquals(-1, checkNotNull(arrived).await(), "the reader inside the close reads the end of file")
        dst.release()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a read after the caller's own close is refused as a misuse`() = readClosedTest {
        // The end of file is for a connection that ended under the caller;
        // a caller reading after its own close is told so, not handed -1.
        val f = Fixture()
        f.channel.ensureBridge()
        f.channel.close()

        val dst = f.tracker.allocate(8)
        assertFailsWith<IllegalStateException> { f.channel.read(dst) }
        dst.release()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a reader parked on an empty queue is woken by the peer's end of file alone`() = readClosedTest {
        // The ordinary shape: a caller waiting with nothing queued, and a
        // peer that just closes. In Coroutine mode the channel does not close
        // on that report, so the wake in the bridge is the only thing that
        // ends the wait — without it the reader waits for good.
        val f = Fixture()
        val dst = f.tracker.allocate(8)
        val reading = async(start = CoroutineStart.UNDISPATCHED) { f.channel.read(dst) }
        assertFalse(reading.isCompleted, "premise: the reader is parked with nothing queued")

        f.peerFin()

        assertEquals(-1, reading.await())
        dst.release()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `the peer's end of file resumes a read the watermark suspended before anyone reads`() = readClosedTest {
        val f = Fixture()
        val bridge = f.channel.ensureBridge()
        f.transport.readEnabled = true
        val chunk = 16 * 1024
        repeat(5) { f.transport.onRead?.invoke(f.tracker.allocate(chunk).also { it.writerIndex = chunk }) }
        assertTrue(bridge.readSuspendedByWatermark, "premise: 80 KiB queued crosses the high watermark")
        assertEquals(1, f.transport.pauseReadsCount)

        f.peerFin()

        // Before any read. A caller that stops reading here never dequeues,
        // so the resume a dequeue below the watermark would perform never
        // happens — and on the engines whose pause leaves the read armed,
        // that resume is the only thing that arms it again and starts the
        // clock this connection is reclaimed by.
        assertEquals(
            1,
            f.transport.resumeReadsCount,
            "the bridge resumes on the end of file, not only when a reader drains the queue",
        )
        assertFalse(bridge.readSuspendedByWatermark)

        val dst = f.tracker.allocate(chunk)
        while (true) {
            dst.clear()
            if (f.channel.read(dst) < 0) break
        }
        dst.release()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a close a handler consumed is still this side's`() = readClosedTest {
        // A handler is allowed to end the close walk where it stands, and
        // then the descriptor is released after the walk instead of at the
        // head. That is still this side closing, so a report arriving
        // afterwards is the transport catching up.
        val f = Fixture()
        f.channel.ensureBridge()
        f.pipeline.addLast(
            "consumer",
            object : DuplexHandler {
                override fun onClose(ctx: PipelineHandlerContext) = Unit
            },
        )
        f.pipeline.addLast(
            "starter",
            object : DuplexHandler {
                override fun onReadClosed(ctx: PipelineHandlerContext) {
                    ctx.propagateClose()
                }
            },
        )

        // A close nobody asked the pipeline for: a handler starts the walk,
        // and the one above it ends it, so the descriptor is released after
        // the walk rather than at the head.
        f.peerFin()
        f.transportEnded()

        assertFalse(f.channel.endedByTransport, "the walk it consumed was this side's close")
        val dst = f.tracker.allocate(8)
        assertFailsWith<IllegalStateException> { f.channel.read(dst) }
        dst.release()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a close still walking to the head is already this side's`() = readClosedTest {
        // A handler writing its farewell from its own close can have the
        // transport refuse it and report the end before the walk reaches the
        // head. The close was asked for by this side, so that report is it
        // catching up, not the connection ending under a caller.
        val f = Fixture()
        f.channel.ensureBridge()
        f.pipeline.addLast(
            "farewell",
            object : DuplexHandler {
                override fun onClose(ctx: PipelineHandlerContext) {
                    f.transportEnded()
                    ctx.propagateClose()
                }
            },
        )

        f.pipeline.requestClose()

        assertFalse(f.channel.endedByTransport, "the close was already this side's when the report landed")
    }

    @Test
    fun `a close asked of the pipeline is not an end under the caller either`() = readClosedTest {
        // The channel sees its own close. A close asked of the pipeline, or
        // walked to the head by a handler, releases the transport just as
        // much — and a report catching up with it must not be read as the
        // connection ending under a caller that closed it.
        val f = Fixture()
        f.channel.ensureBridge()

        f.pipeline.requestClose()
        f.transportEnded()

        assertFalse(f.channel.endedByTransport, "the close was this side's, asked of the pipeline")
        val dst = f.tracker.allocate(8)
        assertFailsWith<IllegalStateException> { f.channel.read(dst) }
        dst.release()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a transport's report from inside this side's own close is not an end under the caller`() = readClosedTest {
        // The close records that it started before it runs anything, because
        // what it runs can bring the report in: a handler's ending releases
        // the transport, and a transport that reports from there would have
        // the caller's own close read as an end under it.
        val f = Fixture()
        f.channel.ensureBridge()
        f.pipeline.addFirst(
            "h",
            object : InboundHandler {
                override fun onInactive(ctx: PipelineHandlerContext) {
                    f.transportEnded()
                }
            },
        )

        f.channel.close()

        assertFalse(f.channel.endedByTransport, "the close was this side's, whatever arrived while it ran")
        val dst = f.tracker.allocate(8)
        assertFailsWith<IllegalStateException> { f.channel.read(dst) }
        dst.release()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a transport's report after a close this side performed is not an end under the caller`() = readClosedTest {
        // The mark separates "the connection ended under the caller" from
        // "the caller closed it", and a report landing after this side's own
        // close — a timer still armed, a loop noticing later — is the second.
        // Marking it would turn the caller's misuse into an end of file.
        val f = Fixture()
        f.channel.ensureBridge()
        f.channel.close()

        f.transportEnded()

        assertFalse(f.channel.endedByTransport, "this side closed it; the transport only caught up")
        val dst = f.tracker.allocate(8)
        assertFailsWith<IllegalStateException> { f.channel.read(dst) }
        dst.release()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `an end of file arriving after this side's own close is still this side's close`() = readClosedTest {
        // The descriptor being gone is not by itself the transport ending the
        // connection: this side may be why it went. A report the loop had
        // queued must not turn the caller's use-after-close into an end of
        // file it can read past.
        val f = Fixture()
        f.channel.ensureBridge()
        f.channel.close()

        f.peerFin()

        assertFalse(f.channel.endedByTransport, "this side closed it, and the report only caught up")
        val dst = f.tracker.allocate(8)
        assertFailsWith<IllegalStateException> { f.channel.read(dst) }
        dst.release()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `an end of file that finds the descriptor gone is an end under the caller`() = readClosedTest {
        // This side chose nothing: the connection was already gone when the
        // report arrived. The close that follows is the channel's answer to
        // that, not a close its caller asked for, so a reader that was away
        // for the moment is owed the end of file.
        val f = Fixture()
        f.channel.ensureBridge()
        f.transport.close()

        f.peerFin()

        assertTrue(f.channel.endedByTransport, "the connection ended under the caller, whatever closed after")
        val dst = f.tracker.allocate(8)
        assertEquals(-1, f.channel.read(dst))
        dst.release()
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `a read after the peer's end of file still arms the transport and starts the clock`() = readClosedTest {
        // This setter is the only place a Coroutine-mode caller arms the
        // read, and arming is what starts the read-idle clock. After the peer
        // finished, that clock is the only claimant left for a connection its
        // caller never closes — so the read arms even though nothing more
        // will arrive on it.
        val f = Fixture()
        f.channel.ensureBridge()
        f.peerFin()
        f.transport.readEnabled = false

        val dst = f.tracker.allocate(8)
        assertEquals(-1, f.channel.read(dst))
        dst.release()

        assertTrue(f.transport.readEnabled, "the clock a half-closed connection is reclaimed by starts here")
        assertEquals(0, f.tracker.outstandingCount)
    }

    @Test
    fun `the mark is read only after the close was seen`() = readClosedTest {
        // One reading decides, and it is the reading of the close: the mark
        // is consulted only once the channel was seen closed. A read that
        // consulted the mark first would hold a stale `false` when the end
        // lands between the two, and refuse the end of file as a misuse. The
        // transport here lands the end inside the mark's reading if there
        // is one before the loop, and otherwise just before the loop runs.
        val tracker = TrackingAllocator()
        var armed = false
        var ended = false
        lateinit var transport: SplitTestIoTransport
        fun landTheEnd() {
            if (!ended) {
                ended = true
                transport.onClosed?.invoke()
            }
        }
        transport = SplitTestIoTransport(tracker)
        transport.dispatcher = object : CoroutineDispatcher() {
            override fun isDispatchNeeded(context: CoroutineContext): Boolean = true

            override fun dispatch(context: CoroutineContext, block: Runnable) {
                if (armed) landTheEnd()
                block.run()
            }
        }
        val channel = object : AbstractPipelinedChannel(transport, PrintLogger("read-closed")) {
            override val endedByTransport: Boolean
                get() {
                    val mark = super.endedByTransport
                    if (armed) landTheEnd()
                    return mark
                }
        }
        channel.ensureBridge()
        armed = true

        val dst = tracker.allocate(8)
        assertEquals(-1, channel.read(dst), "the mark read after the close is the end of file")
        dst.release()
        assertEquals(0, tracker.outstandingCount)
    }
}
