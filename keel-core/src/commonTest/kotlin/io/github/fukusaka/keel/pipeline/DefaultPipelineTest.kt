package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
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

    // Use the canonical [TestIoTransport] directly — its built-in
    // `flushed` and `closed` flags cover the assertions this test needs
    // (the previous private TrackingTransport subclass that added these
    // flags is now redundant with the consolidated fixture).
    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, logger) {}

    private fun createPipeline(): Pipeline = channel.pipeline

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

    // --- Inactive replay (late-installed handlers receive previous onInactive) ---

    @Test
    fun `addLast after notifyInactive replays onInactive on the new handler`() {
        val pipeline = createPipeline()
        pipeline.notifyInactive()

        val late = RecordingInboundHandler()
        pipeline.addLast("late", late)

        assertEquals(listOf("inactive"), late.events)
    }

    @Test
    fun `addFirst addBefore addAfter and replace all replay onInactive after inactive`() {
        val pipeline = createPipeline()
        pipeline.addLast("anchor", RecordingInboundHandler())
        pipeline.notifyInactive()

        val first = RecordingInboundHandler()
        pipeline.addFirst("first", first)
        assertEquals(listOf("inactive"), first.events)

        val before = RecordingInboundHandler()
        pipeline.addBefore("anchor", "before", before)
        assertEquals(listOf("inactive"), before.events)

        val after = RecordingInboundHandler()
        pipeline.addAfter("anchor", "after", after)
        assertEquals(listOf("inactive"), after.events)

        val replacement = RecordingInboundHandler()
        pipeline.replace("anchor", "replacement", replacement)
        assertEquals(listOf("inactive"), replacement.events)
    }

    @Test
    fun `addLast before notifyInactive does not replay — handler receives inactive once via normal dispatch`() {
        val pipeline = createPipeline()
        val handler = RecordingInboundHandler()
        pipeline.addLast("h", handler)
        pipeline.notifyInactive()

        assertEquals(listOf("inactive"), handler.events)
    }

    @Test
    fun `notifyInactive is idempotent — second call does not re-dispatch to existing handlers`() {
        val pipeline = createPipeline()
        val handler = RecordingInboundHandler()
        pipeline.addLast("h", handler)

        pipeline.notifyInactive()
        pipeline.notifyInactive()
        pipeline.notifyInactive()

        assertEquals(listOf("inactive"), handler.events)
    }

    @Test
    fun `outbound-only handler does not receive replayed onInactive`() {
        val pipeline = createPipeline()
        pipeline.notifyInactive()

        val outbound = RecordingOutboundHandler()
        pipeline.addLast("out", outbound)

        assertTrue(outbound.events.isEmpty())
    }

    @Test
    fun `replayed onInactive exception is logged and does not abort handler installation`() {
        val pipeline = createPipeline()
        pipeline.notifyInactive()

        val failing = object : InboundHandler {
            override fun onInactive(ctx: PipelineHandlerContext) {
                throw RuntimeException("replay error")
            }
        }
        // Must not throw — logger swallows the replay exception and pipeline
        // state stays consistent.
        pipeline.addLast("failing", failing)

        // Pipeline still accepts subsequent installs and replays for them.
        val later = RecordingInboundHandler()
        pipeline.addLast("later", later)
        assertEquals(listOf("inactive"), later.events)
    }

    @Test
    fun `handler removed then re-added after inactive receives onInactive again`() {
        val pipeline = createPipeline()
        val handler = RecordingInboundHandler()
        pipeline.addLast("h", handler)
        pipeline.notifyInactive()
        // Normal dispatch fired once.
        assertEquals(listOf("inactive"), handler.events)

        pipeline.remove("h")
        // Re-add the same handler instance — the replay should fire onInactive
        // a second time because the inactive state has been observed.
        pipeline.addLast("h", handler)

        assertEquals(listOf("inactive", "inactive"), handler.events)
    }

    @Test
    fun `handler installed during onInactive walk still observes inactive`() {
        val pipeline = createPipeline()
        val laterRef = arrayOfNulls<RecordingInboundHandler>(1)
        val firstHandler = object : InboundHandler {
            override fun onInactive(ctx: PipelineHandlerContext) {
                // Install a new handler from inside onInactive — exercises
                // the boundary where [notifyInactive] is mid-walk and the
                // pipeline-level `inactiveObserved` flag has already been
                // set. The replay path in [callHandlerAdded] guarantees the
                // late handler is notified at least once.
                //
                // Note: the in-progress walk may also reach the new handler
                // via [PipelineHandlerContext.propagateInactive], so handlers
                // observing inactivation must remain idempotent. The
                // canonical [io.github.fukusaka.keel.pipeline.SuspendBridgeHandler]
                // satisfies this — it sets `eof = true` and resumes the
                // pending continuation (no-op on a resumed continuation).
                laterRef[0] = RecordingInboundHandler().also {
                    pipeline.addLast("nested-late", it)
                }
                ctx.propagateInactive()
            }
        }
        pipeline.addLast("first", firstHandler)

        pipeline.notifyInactive()

        // The nested handler must observe inactive at least once. Whether
        // the in-progress walk also dispatches in addition to the replay is
        // implementation-defined; both are acceptable as long as handlers
        // are idempotent.
        val events = laterRef[0]!!.events
        assertTrue(
            events.all { it == "inactive" } && events.isNotEmpty(),
            "expected one or more 'inactive' events, got: $events",
        )
    }

    // --- Pre-attach event journal ---
    //
    // These tests exercise the journal infrastructure that buffers
    // inbound events arriving before the pipeline acquires its first
    // user [InboundHandler]. With [TestIoTransport]'s
    // [Dispatchers.Unconfined] backing, drain runs inline on the first
    // user inbound `addX` (the deferred dispatcher path is exercised by
    // the engine integration tests for [IdleReadPolicy.DETECT_PEER_CLOSE]).

    @Test
    fun `pre-attach journal preserves notifyRead delivered before any user handler`() {
        val pipeline = createPipeline()
        // Engine-side notifyRead arrives before the user installs a handler.
        // Without the journal this would reach TailHandler.onRead and be
        // released with a WARN log; with the journal the message is buffered.
        pipeline.notifyRead("payload")

        val handler = RecordingInboundHandler()
        pipeline.addLast("h", handler)

        // Drain replays the buffered read through the now-assembled chain.
        assertEquals(listOf("read"), handler.events)
        assertEquals("payload", handler.lastMsg)
    }

    @Test
    fun `pre-attach journal preserves multiple notifyRead in order`() {
        val pipeline = createPipeline()
        pipeline.notifyRead("first")
        pipeline.notifyRead("second")
        pipeline.notifyRead("third")

        val handler = RecordingInboundHandler()
        pipeline.addLast("h", handler)

        // FIFO order preservation is essential for stream protocols.
        assertEquals(listOf("read", "read", "read"), handler.events)
    }

    @Test
    fun `pre-attach journal replays notifyActive once then reads then readComplete`() {
        val pipeline = createPipeline()
        pipeline.notifyActive()
        pipeline.notifyActive() // idempotent — coalesces into single replay
        pipeline.notifyRead("data")
        pipeline.notifyReadComplete()
        pipeline.notifyReadComplete() // idempotent — coalesces

        val handler = RecordingInboundHandler()
        pipeline.addLast("h", handler)

        assertEquals(listOf("active", "read", "readComplete"), handler.events)
    }

    @Test
    fun `pre-attach journal replays writability with latest value only`() {
        val pipeline = createPipeline()
        pipeline.notifyWritabilityChanged(true)
        pipeline.notifyWritabilityChanged(false)
        pipeline.notifyWritabilityChanged(true) // latest

        val recorded = mutableListOf<Boolean>()
        val handler = object : InboundHandler {
            override val acceptedType: KClass<*> = Any::class
            override val producedType: KClass<*> = Any::class
            override fun onWritabilityChanged(ctx: PipelineHandlerContext, isWritable: Boolean) {
                recorded.add(isWritable)
                ctx.propagateWritabilityChanged(isWritable)
            }
        }
        pipeline.addLast("h", handler)

        // Latest-only: only the most recent (`true`) is replayed.
        assertEquals(listOf(true), recorded)
    }

    @Test
    fun `pre-attach journal replays notifyError`() {
        val pipeline = createPipeline()
        pipeline.notifyError(RuntimeException("boom"))

        val handler = RecordingInboundHandler()
        pipeline.addLast("h", handler)

        assertEquals(listOf("error:boom"), handler.events)
    }

    @Test
    fun `pre-attach journal replays notifyUserEvent`() {
        val pipeline = createPipeline()
        pipeline.notifyUserEvent("upgrade")

        val handler = RecordingInboundHandler()
        pipeline.addLast("h", handler)

        assertEquals(listOf("userEvent:upgrade"), handler.events)
    }

    @Test
    fun `pre-attach journal replays notifyInactive without doubling per-handler replay`() {
        val pipeline = createPipeline()
        pipeline.notifyInactive()

        val handler = RecordingInboundHandler()
        pipeline.addLast("h", handler)

        // The journal drain delivers onInactive via head propagation.
        // The per-handler replay in callHandlerAdded must skip when
        // drain just fired inline so onInactive is not double-delivered.
        assertEquals(listOf("inactive"), handler.events)
    }

    @Test
    fun `events arriving after first handler bypass the journal`() {
        val pipeline = createPipeline()
        val handler = RecordingInboundHandler()
        pipeline.addLast("h", handler)
        // After drain, subsequent notifyXxx propagate directly through head.
        pipeline.notifyRead("after-drain")

        assertEquals(listOf("read"), handler.events)
        assertEquals("after-drain", handler.lastMsg)
    }

    // --- Per-handler lifecycle replay (late-added handlers receive current state) ---
    //
    // These tests cover the generalisation of the existing
    // `inactiveObserved` per-handler replay to all three lifecycle
    // events (`onActive`, `onWritabilityChanged`, `onInactive`). A
    // handler added after the chain has already received a lifecycle
    // event must observe the current state so it can act correctly
    // (e.g. a metrics handler joining a running channel needs to know
    // it is active; a parser added after peer FIN needs to clean up).

    @Test
    fun `addLast after notifyActive replays onActive on the new handler`() {
        val pipeline = createPipeline()
        // First handler triggers drain inline; activeFired set during drain.
        val first = RecordingInboundHandler()
        pipeline.notifyActive()
        pipeline.addLast("first", first)
        // Sanity: first observed active via the drain replay.
        assertEquals(listOf("active"), first.events)

        // Late-added handler joins an already-active channel.
        val late = RecordingInboundHandler()
        pipeline.addLast("late", late)

        assertEquals(listOf("active"), late.events)
    }

    @Test
    fun `addLast after notifyActive plus writabilityChanged replays both with latest writability`() {
        val pipeline = createPipeline()
        pipeline.notifyActive()
        // First handler installs the chain — drain replays active for it.
        val first = RecordingInboundHandler()
        pipeline.addLast("first", first)
        // Update writability after drain; writabilityCurrent is set.
        pipeline.notifyWritabilityChanged(true)
        pipeline.notifyWritabilityChanged(false)
        pipeline.notifyWritabilityChanged(true) // latest

        // Late-added handler should see active + the latest writability.
        val recorded = mutableListOf<String>()
        val late = object : InboundHandler {
            override val acceptedType: KClass<*> = Any::class
            override val producedType: KClass<*> = Any::class
            override fun onActive(ctx: PipelineHandlerContext) {
                recorded.add("active")
                ctx.propagateActive()
            }
            override fun onWritabilityChanged(ctx: PipelineHandlerContext, isWritable: Boolean) {
                recorded.add("writability:$isWritable")
                ctx.propagateWritabilityChanged(isWritable)
            }
        }
        pipeline.addLast("late", late)

        assertEquals(listOf("active", "writability:true"), recorded)
    }

    @Test
    fun `per-handler replay terminal-state-wins — late handler after active and inactive sees only inactive`() {
        val pipeline = createPipeline()
        // Install first handler so the chain is non-empty; drain runs
        // with an empty journal (no pre-attach events) and the
        // following notifyXxx are post-drain.
        val first = RecordingInboundHandler()
        pipeline.addLast("first", first)
        pipeline.notifyActive()
        pipeline.notifyInactive()
        // Sanity: first observed both via head propagation.
        assertEquals(listOf("active", "inactive"), first.events)

        // Late handler joins now — channel is in terminal inactive
        // state. Per-handler replay must observe `onInactive` only,
        // because replaying `onActive` would confuse cleanup logic
        // that conditions on isActive.
        val late = RecordingInboundHandler()
        pipeline.addLast("late", late)

        assertEquals(listOf("inactive"), late.events)
    }

    @Test
    fun `drain preserves causal order — pre-attach active then inactive both deliver to first handler`() {
        val pipeline = createPipeline()
        pipeline.notifyActive()
        pipeline.notifyInactive()

        // The first handler installs the chain; drain replays both
        // events in causal order (active before inactive). This
        // differs from the post-drain per-handler replay where
        // terminal state suppresses `onActive` — at drain time, the
        // chain has not yet observed any event, so the full sequence
        // is delivered for protocol correctness (e.g. a handler
        // tracking lifecycle counts must observe each transition).
        val handler = RecordingInboundHandler()
        pipeline.addLast("h", handler)

        assertEquals(listOf("active", "inactive"), handler.events)
    }

    @Test
    fun `lifecycle replay does not double-fire on the handler that triggered drain`() {
        val pipeline = createPipeline()
        // Pre-attach: notifyActive + notifyWritabilityChanged
        pipeline.notifyActive()
        pipeline.notifyWritabilityChanged(true)

        // First handler triggers drain inline; drain propagates active +
        // writability through head (which reaches this handler).
        // Per-handler replay must NOT fire again or the handler sees
        // each event twice.
        val first = RecordingInboundHandler()
        pipeline.addLast("first", first)

        // Single set of events — drain via head only.
        assertEquals(listOf("active"), first.events)
    }

    @Test
    fun `pre-attach journal does not drain on outbound-only addLast`() {
        val pipeline = createPipeline()
        pipeline.notifyRead("queued")

        // Adding an outbound-only handler does not trigger drain — the
        // journal is keyed on InboundHandler addition because data flows
        // through the inbound chain.
        val outbound = object : OutboundHandler {
            override fun onWrite(ctx: PipelineHandlerContext, msg: Any) = ctx.propagateWrite(msg)
            override fun onFlush(ctx: PipelineHandlerContext) = ctx.propagateFlush()
            override fun onClose(ctx: PipelineHandlerContext) = ctx.propagateClose()
        }
        pipeline.addLast("out", outbound)

        // Now add the inbound handler — drain replays the queued read.
        val inbound = RecordingInboundHandler()
        pipeline.addLast("in", inbound)

        assertEquals(listOf("read"), inbound.events)
    }
}
