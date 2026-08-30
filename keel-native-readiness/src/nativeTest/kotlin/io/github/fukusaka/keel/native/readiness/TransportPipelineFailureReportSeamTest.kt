@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.native.readiness

import io.github.fukusaka.keel.core.RefusedWriteException
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.native.posix.WriteResult
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.OutboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.buf.FailingReleaseIoBuf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.EPIPE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The head's record wording, shared by every matcher in this suite so a
 * production reword is a one-line edit here — and so the negative matchers
 * provably test the same string the positive ones prove against production.
 */
private const val ENDED_AT_HEAD = "ended at the head before any handler had it"

/**
 * Pins what a pipeline handler hears when a refused send ends the connection:
 * the refusal on [DuplexHandler.onError] first, the end on
 * [DuplexHandler.onInactive] second, each exactly once — in both flush
 * configurations, from the flush, readiness, and half-close entries. (The
 * register-time short-circuit shares the same drain funnel and is not
 * exercised separately here.)
 *
 * The order is the contract's, not the implementation's: `onInactive` is the
 * handler's cue to clean up, and a reason delivered after the cleanup reaches
 * nobody who can act on it. The borrowed model orders it the same way — Netty
 * fires `exceptionCaught` before `channelInactive` on every path that fires
 * both.
 *
 * A refusal met while the caller is already closing is deliberately not
 * reported: the caller asked for the connection to end and the queue to be
 * discarded, so a dead peer met while discarding is the outcome it asked for.
 * Neither is one met after the inactive already went out — the peer can end
 * the connection first — since reporting there would put the reason after
 * the end it is contracted to precede.
 *
 * The report is offered wherever there is a pipeline to offer it to,
 * including a Coroutine-mode one whose caller is answered by the suspending
 * wait it already makes. A channel with nothing installed is the exception:
 * there is nobody to tell, so the head keeps the record instead — quietly
 * when the send is all that failed, loudly when something failed with it,
 * which is the level the end of the pipeline uses for the same reason.
 */
@OptIn(ExperimentalForeignApi::class)
internal class TransportPipelineFailureReportSeamTest : TransportSeamFixture() {

    private class Recorder : DuplexHandler {
        val seen = mutableListOf<String>()
        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            seen += "onError(${cause::class.simpleName})"
        }

        override fun onInactive(ctx: PipelineHandlerContext) {
            seen += "onInactive"
        }
    }

    private fun refusedScenario(
        coalescing: Boolean,
        act: (AbstractPipelinedChannel, ReadinessIoTransport) -> Unit,
    ): List<String> {
        var out: List<String> = emptyList()
        runBlocking {
            withTimeout(FUNNEL_TIMEOUT_MS) {
                rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = coalescing)
                fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
                val transport = transport()
                val ch = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}
                val rec = Recorder()
                ch.pipeline.addLast("rec", rec)
                transport.write(tracker.allocate(16).apply { writerIndex = 5 })
                runCatching { act(ch, transport) }
                runCatching { eventLoop.drainDispatched() }
                runCatching { transport.close() }
                runCatching { eventLoop.drainDispatched() }
                tracker.assertNoLeaks()
                out = rec.seen.toList()
            }
        }
        return out
    }

    private val expected = listOf("onError(RefusedWriteException)", "onInactive")

    @Test
    fun `a refused flush reports the failure and then the end on the shipping default`() {
        assertEquals(expected, refusedScenario(coalescing = true) { ch, _ -> ch.requestFlush() })
    }

    @Test
    fun `a refused flush reports the same under the coalescing opt-out`() {
        assertEquals(expected, refusedScenario(coalescing = false) { ch, _ -> ch.requestFlush() })
    }

    @Test
    fun `a refusal met by write readiness reports the same`() {
        assertEquals(expected, refusedScenario(coalescing = true) { _, t -> t.onReady(Interest.WRITE) })
    }

    @Test
    fun `a refusal met by write readiness reports the same under the opt-out`() {
        assertEquals(expected, refusedScenario(coalescing = false) { _, t -> t.onReady(Interest.WRITE) })
    }

    @Test
    fun `a refused half-close reports the same on the shipping default`() {
        assertEquals(expected, refusedScenario(coalescing = true) { ch, _ -> ch.shutdownOutput() })
    }

    @Test
    fun `a refused half-close reports the same under the opt-out`() {
        assertEquals(expected, refusedScenario(coalescing = false) { ch, _ -> ch.shutdownOutput() })
    }

    @Test
    fun `a handler that writes and flushes from onError is not re-entered`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The report runs while the transport still accepts writes -- the
            // wind-down is deliberately after it. A handler answering the
            // error by sending something (the ordinary error-response shape)
            // re-enters the drain synchronously under the coalescing opt-out
            // and meets the same dead peer. That second refusal must not
            // become a second report: the first is the connection's reason.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE), WriteResult.Failed(EPIPE))
            val transport = transport()
            val ch = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}
            val seen = mutableListOf<String>()
            var firstCause: Throwable? = null
            ch.pipeline.addLast(
                "responder",
                object : DuplexHandler {
                    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                        seen += "onError"
                        if (firstCause == null) {
                            firstCause = cause
                            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
                            ch.requestFlush()
                        }
                    }

                    override fun onInactive(ctx: PipelineHandlerContext) {
                        seen += "onInactive"
                    }
                },
            )
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertEquals(listOf("onError", "onInactive"), seen, "the nested refusal is not a second report")
            val awaited = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()
            assertSame(
                firstCause,
                awaited,
                "the recorded reason is the first refusal, not the nested one",
            )
            fake.assertAllConsumed()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a rider on a refusal the close race silences is named in the log`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The refusal itself going quiet is the caller-close design; the
            // release failure riding on it is a leak, and the head -- the one
            // frame that silences -- names it in the log. Deliberately not
            // handed back to the handlers: re-entry from the head is the
            // recursion channel.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            val rec = Recorder()
            ch.pipeline.addLast("rec", rec)
            val over = HIGH_WATER + 1
            val failing = FailingReleaseIoBuf(tracker.allocate(over).apply { writerIndex = over })
            transport.onWritabilityChanged = { writable -> if (writable) transport.close() }
            transport.write(failing)

            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertTrue(
                plog.warnings.any { ENDED_AT_HEAD in it },
                "the rider must be named: ${plog.warnings}",
            )
            assertEquals(emptyList(), rec.seen, "the refusal stays quiet and no handler is re-entered")
            fake.assertAllConsumed()
            failing.releaseUnderlying()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a rider on a nested refusal is named without re-entering the handler`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The other silenced arm. The nested refusal is quiet because
            // the connection's reason is already the first one; its rider is
            // a leak and gets a name in the log -- not another onError,
            // which would let a handler that answers every error with
            // another doomed write recurse until the stack ran out.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE), WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            val seen = mutableListOf<String>()
            ch.pipeline.addLast(
                "responder",
                object : DuplexHandler {
                    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                        seen += "onError(${cause::class.simpleName})"
                        transport.write(failing)
                        ch.requestFlush()
                    }

                    override fun onInactive(ctx: PipelineHandlerContext) {
                        seen += "onInactive"
                    }
                },
            )
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertEquals(
                listOf("onError(RefusedWriteException)", "onInactive"),
                seen,
                "an always-answering handler is entered once, not recursed into",
            )
            assertTrue(
                plog.warnings.any { ENDED_AT_HEAD in it },
                "and its rider is still named: ${plog.warnings}",
            )
            fake.assertAllConsumed()
            failing.releaseUnderlying()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a reported refusal's riders arrive attached and are not named again`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The reported arm of the same check: the funnel delivered the
            // refusal with its riders attached, so the head naming them
            // again would report one leak twice -- after the inactive that
            // the reason is contracted to precede.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            val rec = Recorder()
            ch.pipeline.addLast("rec", rec)
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertEquals(
                listOf("onError(RefusedWriteException)", "onInactive"),
                rec.seen,
                "the riders came attached to the reported refusal, nothing arrives after the end",
            )
            assertTrue(
                plog.warnings.none { ENDED_AT_HEAD in it },
                "and the head does not name them a second time: ${plog.warnings}",
            )
            fake.assertAllConsumed()
            failing.releaseUnderlying()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a reported refusal riding on an outer one is not mistaken for a leak`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The writability crossing runs handler code inside the outer
            // drain's ledger update, before the outer catch. A handler that
            // answers writability with a doomed write makes the *nested*
            // drain the first -- reported -- refusal; its rethrow is carried
            // as a suppressed cause on the outer one, which the head then
            // sees as an unreported refusal with a rider. That rider is the
            // reported instance itself, already delivered attached, not a
            // leak to name.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE), WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            val rec = Recorder()
            ch.pipeline.addLast("rec", rec)
            val over = HIGH_WATER + 1
            var answered = false
            transport.onWritabilityChanged = { writable ->
                if (writable && !answered) {
                    answered = true
                    transport.write(tracker.allocate(16).apply { writerIndex = 5 })
                    // Direct flush, not requestFlush: through the head the
                    // nested throw is consumed by the head's own catch; the
                    // direct call lets it unwind into the outer drain's
                    // ledger stage, which carries it as a suppressed cause
                    // on the outer refusal -- the shape under test.
                    transport.flush()
                }
            }
            transport.write(tracker.allocate(over).apply { writerIndex = over })

            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertEquals(
                listOf("onError(RefusedWriteException)", "onInactive"),
                rec.seen,
                "the nested refusal is the reported one, once, in order",
            )
            assertEquals(
                1,
                plog.warnings.count { ENDED_AT_HEAD in it },
                "the outer refusal reached nobody, so it is recorded once: ${plog.warnings}",
            )
            fake.assertAllConsumed()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a real rider is still named once when the reported instance rides along`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The previous shape with the outer buffer's release also
            // failing: the release failure is now the outer refusal's rider,
            // and the reported nested instance rides suppressed on that
            // leak rather than on the outer refusal directly -- the ledger
            // attaches later failures to the first. Filtering out the
            // reported instance must not silence the genuine leak carrying
            // it, and one catch frame names it exactly once.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE), WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            val rec = Recorder()
            ch.pipeline.addLast("rec", rec)
            val over = HIGH_WATER + 1
            val failing = FailingReleaseIoBuf(tracker.allocate(over).apply { writerIndex = over })
            var answered = false
            transport.onWritabilityChanged = { writable ->
                if (writable && !answered) {
                    answered = true
                    transport.write(tracker.allocate(16).apply { writerIndex = 5 })
                    transport.flush()
                }
            }
            transport.write(failing)

            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertEquals(
                listOf("onError(RefusedWriteException)", "onInactive"),
                rec.seen,
                "the nested refusal is the reported one, once, in order",
            )
            assertEquals(
                1,
                plog.warnings.count { ENDED_AT_HEAD in it },
                "the genuine leak is named exactly once: ${plog.warnings}",
            )
            fake.assertAllConsumed()
            failing.releaseUnderlying()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refusal after the peer already ended the connection is not reported`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The inactive can precede the refusal: a peer FIN reports it,
            // and a handler that answers its own onInactive with a final
            // flush meets the dead peer afterwards. A reason delivered after
            // the end reaches nobody who can act on it, so this refusal
            // answers the wait but is not an error to report -- reporting it
            // would put onError after the onInactive it is contracted to
            // precede. Not reported is not the same as not recorded: these
            // handlers never hear this refusal, so the head keeps it.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            val seen = mutableListOf<String>()
            ch.pipeline.addLast(
                "finisher",
                object : DuplexHandler {
                    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                        seen += "onError(${cause::class.simpleName})"
                    }

                    override fun onInactive(ctx: PipelineHandlerContext) {
                        seen += "onInactive"
                        transport.write(tracker.allocate(16).apply { writerIndex = 5 })
                        ch.requestFlush()
                    }
                },
            )

            runCatching { transport.onPeerClosed(Interest.READ) }
            runCatching { eventLoop.drainDispatched() }

            assertEquals(listOf("onInactive"), seen, "no reason arrives after the end")
            val awaited = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()
            assertIs<RefusedWriteException>(awaited, "the wait is still answered with the refusal")
            assertEquals(
                1,
                plog.records.count { it.first == LogLevel.DEBUG && ENDED_AT_HEAD in it.second },
                "the handler was told the end, never this refusal, so it is recorded: ${plog.records}",
            )
            fake.assertAllConsumed()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a rider on a refusal no handler has heard yet is named in the log`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // A channel with no handlers is not offered the report at all,
            // so nobody has heard the riders -- and a failed release riding
            // along has no other reporter. Silence here would break the rule
            // the check exists for: a leak is never silent.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertEquals(
                1,
                plog.warnings.count { ENDED_AT_HEAD in it },
                "the rider is named once even with nobody attached: ${plog.warnings}",
            )
            fake.assertAllConsumed()
            failing.releaseUnderlying()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refusal with nothing riding on it is recorded too`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The same shape without a rider. Nothing rode along, so nothing
            // here asks to be looked into -- but the reason the connection
            // ended is still kept, at the level a reader goes looking for
            // rather than the one that comes to them.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            ch.pipeline.addLast("outbound-only", object : OutboundHandler {})
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertEquals(
                1,
                plog.records.count { it.first == LogLevel.DEBUG && ENDED_AT_HEAD in it.second },
                "the connection ended for a reason nobody was told: ${plog.records}",
            )
            assertIs<RefusedWriteException>(
                plog.causeOfDebug(ENDED_AT_HEAD),
                "and the record carries the refusal itself: ${plog.records}",
            )
            fake.assertAllConsumed()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refusal application code minted is recorded honestly and settles nothing`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The record's wording is what this pins: the head cannot see
            // whether a settlement ran, and this input is the one where none
            // did -- a completion callback throwing the public refusal type
            // itself. The transport minted nothing, so nothing was recorded
            // and the connection is not ended over an exception it never
            // raised; the head's record must not claim a containment that
            // did not happen.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Written(5))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            ch.pipeline.addLast("outbound-only", object : OutboundHandler {})
            val minted = RefusedWriteException("application-minted refusal")
            transport.onFlushComplete = { throw minted }
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertTrue(transport.isOpen, "a refusal the transport never raised must not end the connection")
            assertEquals(
                1,
                plog.records.count { it.first == LogLevel.DEBUG && ENDED_AT_HEAD in it.second },
                "the head records the arrival without claiming a settlement: ${plog.records}",
            )
            assertSame(
                minted,
                plog.causeOfDebug(ENDED_AT_HEAD),
                "and the record carries the application's own instance",
            )
            fake.assertAllConsumed()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `on the shipping default the record is left to the loop`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The same pipeline that has nobody to tell, with coalescing on:
            // the flush schedules the drain instead of running it, so the
            // refusal is met on a later tick inside the loop's containment,
            // which names it there. The head is never handed the rethrow and
            // must not record a second line for the same send.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = true)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            ch.pipeline.addLast("outbound-only", object : OutboundHandler {})
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertTrue(
                plog.warnings.none { ENDED_AT_HEAD in it },
                "the loop names this one, so the pipeline stays out of it: ${plog.warnings}",
            )
            assertTrue(
                eventLoop.logger.warnings.any { "ending the connection" in it },
                "and the loop does name it: ${eventLoop.logger.warnings}",
            )
            fake.assertAllConsumed()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refusal no handler will receive is recorded where it is accepted`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The replay is scheduled by the first inbound handler, so a
            // pipeline whose handlers are all outbound never asks for one and
            // nothing here will hand the reason over. The pipeline records it
            // as it accepts it -- with its riders along -- because no frame
            // after this one can: the head stays quiet for a refusal it can
            // see was taken on, and under the coalescing opt-out the drain
            // ran inside this flush, so no loop containment saw it either.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            ch.pipeline.addLast("outbound-only", object : OutboundHandler {})
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertEquals(
                1,
                plog.warnings.count { ENDED_AT_HEAD in it },
                "nothing will hand it over, so it is recorded here: ${plog.warnings}",
            )
            assertTrue(
                plog.causeOfWarning(ENDED_AT_HEAD)
                    ?.suppressedExceptions?.isNotEmpty() == true,
                "and the rider rides on the record: ${plog.warnings}",
            )
            fake.assertAllConsumed()
            failing.releaseUnderlying()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a rider on a report still sitting in the journal is left to the replay`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // Adding a handler schedules the journal replay on the
            // dispatcher rather than running it inline, so a codec stack
            // added back-to-back accumulates first. A refusal met inside
            // that window has a reporter -- the drain is already scheduled,
            // and hands it over with its riders attached (or reports it as
            // discarded) -- but that runs after the head has
            // already swallowed the rethrow. So the head has to count it as
            // reported when the pipeline accepts it: naming it here would
            // report one leak twice, once in the log and once to the
            // handler about to receive it.
            rebuildLoop(onLoopThread = true, runDispatchedInline = false, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            val rec = Recorder()
            ch.pipeline.addLast("rec", rec)
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            runCatching { ch.requestFlush() }

            assertTrue(
                plog.warnings.none { ENDED_AT_HEAD in it },
                "the replay is the reporter, so the head does not name it too: ${plog.warnings}",
            )
            runCatching { eventLoop.drainDispatched() }
            // Order, not just arrival: the head handed the reporting over,
            // so the contract it stayed quiet for has to hold on the other
            // side of the handover too.
            assertEquals(expected, rec.seen, "the replay delivers the reason, still before the end")
            fake.assertAllConsumed()
            failing.releaseUnderlying()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a bridged channel is answered by its own API and told quietly`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // A Coroutine-mode channel's caller learns the refusal from the
            // suspending wait it already makes, so the reason travelling the
            // pipeline has nobody to inform -- and nothing to complain about
            // either: the end of the pipeline knows the send the transport
            // reported from an exception nobody handled.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            ch.ensureBridge()
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })

            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertTrue(
                plog.warnings.isEmpty(),
                "nothing here is worth a reader's attention: ${plog.warnings}",
            )
            assertTrue(
                plog.records.any {
                    it.first == LogLevel.DEBUG && "reached the end of the pipeline" in it.second
                },
                "and the reason was offered to it, rather than kept from it: ${plog.records}",
            )
            val awaited = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()
            assertIs<RefusedWriteException>(awaited, "and that API still answers with the refusal")
            fake.assertAllConsumed()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `an error a handler injects itself does not turn delivered riders into a leak report`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The head tells a delivered refusal from a silenced one by
            // identity. If a handler's own injected error moved that mark,
            // the refusal it already heard -- riders attached -- would come
            // back as a leak nobody reported.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            val seen = mutableListOf<String>()
            ch.pipeline.addLast(
                "injector",
                object : DuplexHandler {
                    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                        seen += "onError(${cause::class.simpleName})"
                        if (cause is RefusedWriteException) {
                            ch.pipeline.notifyError(IllegalStateException("a diagnostic of my own"))
                        }
                    }
                },
            )
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            transport.write(failing)

            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertTrue(
                plog.warnings.none { ENDED_AT_HEAD in it },
                "the riders arrived attached to the delivered refusal: ${plog.warnings}",
            )
            assertEquals(
                listOf("onError(RefusedWriteException)", "onError(IllegalStateException)"),
                seen,
                "both errors reached the handler",
            )
            fake.assertAllConsumed()
            failing.releaseUnderlying()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a rider on a refusal met after a peer-first end is still named in the log`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The third quiet arm carrying a rider: the refusal met after the
            // inactive went out stays quiet by design, but the failed release
            // riding on it is a leak with no other reporter -- the head names
            // it once, in the log, with no handler entered after the end.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = false)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val plog = RecordingLogger()
            val ch = object : AbstractPipelinedChannel(transport, plog) {}
            val failing = FailingReleaseIoBuf(tracker.allocate(16).apply { writerIndex = 5 })
            val seen = mutableListOf<String>()
            ch.pipeline.addLast(
                "finisher",
                object : DuplexHandler {
                    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                        seen += "onError(${cause::class.simpleName})"
                    }

                    override fun onInactive(ctx: PipelineHandlerContext) {
                        seen += "onInactive"
                        transport.write(failing)
                        ch.requestFlush()
                    }
                },
            )

            runCatching { transport.onPeerClosed(Interest.READ) }
            runCatching { eventLoop.drainDispatched() }

            assertEquals(listOf("onInactive"), seen, "the quiet arm enters no handler")
            assertEquals(
                1,
                plog.warnings.count { ENDED_AT_HEAD in it },
                "the rider is named exactly once: ${plog.warnings}",
            )
            fake.assertAllConsumed()
            failing.releaseUnderlying()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a refusal met while the caller is closing is not reported`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The close's own teardown runs the scheduled drain and meets the
            // refusal with the connection already ending. The caller asked
            // for the queue to be discarded; a dead peer met while
            // discarding is not an error the pipeline is owed.
            rebuildLoop(onLoopThread = true, runDispatchedInline = false, flushCoalescing = true)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val ch = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}
            val rec = Recorder()
            ch.pipeline.addLast("rec", rec)
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            assertFalse(transport.flush(), "coalescing schedules the drain the close will run")

            transport.close()
            runCatching { eventLoop.drainDispatched() }

            assertTrue(
                rec.seen.none { it.startsWith("onError") },
                "the caller's own close is not an error to report: ${rec.seen}",
            )
            val awaited = runCatching { transport.awaitPendingFlush() }.exceptionOrNull()
            assertIs<RefusedWriteException>(
                awaited,
                "and a wait still asks a different question, answered with the refusal",
            )
            fake.assertAllConsumed()
            tracker.assertNoLeaks()
        }
    }

    @Test
    fun `a handler that throws in onError is still told the end`() = runBlocking {
        withTimeout(FUNNEL_TIMEOUT_MS) {
            // The report runs user code before the wind-down, so a throwing
            // handler is the seam case: it heard the report and failed while
            // handling it -- the pipeline contains that -- and the wind-down
            // must still run.
            rebuildLoop(onLoopThread = true, runDispatchedInline = true, flushCoalescing = true)
            fake.enqueueWrite(fd, WriteResult.Failed(EPIPE))
            val transport = transport()
            val ch = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}
            val seen = mutableListOf<String>()
            ch.pipeline.addLast(
                "throwing",
                object : DuplexHandler {
                    override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                        seen += "onError"
                        throw IllegalStateException("handler failed while handling the report")
                    }

                    override fun onInactive(ctx: PipelineHandlerContext) {
                        seen += "onInactive"
                    }
                },
            )
            transport.write(tracker.allocate(16).apply { writerIndex = 5 })
            runCatching { ch.requestFlush() }
            runCatching { eventLoop.drainDispatched() }

            assertEquals(listOf("onError", "onInactive"), seen, "the wind-down survives the handler's throw")
            assertFalse(transport.isOpen, "and the connection still ends")
            fake.assertAllConsumed()
            runCatching { transport.close() }
            tracker.assertNoLeaks()
        }
    }
}
