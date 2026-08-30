package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.core.EngineFailureException
import io.github.fukusaka.keel.core.RefusedWriteException
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The head's record wording, shared by the matchers below so a production
 * reword is a one-line edit here.
 */
private const val ENDED_AT_HEAD = "ended at the head before any handler had it"

/**
 * Pins the decisions this module makes about a transport-forced failure,
 * in this module — where they are written.
 *
 * The engine seam tests exercise the same decisions through a real drain,
 * but only on the native readiness engines; the predicate that chooses a
 * destination and the mark that tells a reported refusal from a silenced
 * one live in `commonMain` and are the same on every target.
 */
class PipelineTransportFailureTest {

    private class RefusingTransport(
        private val rider: Throwable? = null,
    ) : TestIoTransport() {

        /** The instance the funnel reports and then rethrows, as a real one does. */
        val refusal: RefusedWriteException = RefusedWriteException("peer is gone").apply {
            rider?.let { addSuppressed(it) }
        }

        override fun flush(): Boolean {
            onConnectionFailure?.invoke(refusal)
            throw refusal
        }
    }

    private class RecordingLogger : Logger {
        val records = mutableListOf<Triple<LogLevel, String, Throwable?>>()
        val warnings: List<String> get() = records.filter { it.first == LogLevel.WARN }.map { it.second }
        fun causeOf(fragment: String): Throwable? =
            records.firstOrNull { it.first == LogLevel.WARN && fragment in it.second }?.third

        override fun isLoggable(level: LogLevel) = true
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            records.add(Triple(level, message.toString(), throwable))
        }
    }

    private class Recorder : DuplexHandler {
        val seen = mutableListOf<String>()
        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
            seen += "onError(${cause::class.simpleName})"
        }
    }

    private fun channel(transport: IoTransport, logger: Logger): AbstractPipelinedChannel =
        object : AbstractPipelinedChannel(transport, logger) {}

    @Test
    fun `a handler installed to act on the reason is told about it`() {
        val transport = RefusingTransport()
        val ch = channel(transport, RecordingLogger())
        val rec = Recorder()
        ch.pipeline.addLast("rec", rec)

        runCatching { ch.requestFlush() }

        assertEquals(listOf("onError(RefusedWriteException)"), rec.seen)
    }

    @Test
    fun `a handler nobody wrote an onError for is not told it has a bug`() {
        // The reason travels until a handler acts on it, and most have
        // nothing to do with it that the end does not already tell them --
        // so the end of the pipeline records it as what it is, rather than
        // as an exception nobody handled.
        val transport = RefusingTransport()
        val log = RecordingLogger()
        val ch = channel(transport, log)
        ch.pipeline.addLast("inbound-only", object : InboundHandler {})

        runCatching { ch.requestFlush() }

        assertTrue(
            log.warnings.none { "Unhandled" in it },
            "a peer disappearing mid-write is not an application bug: ${log.warnings}",
        )
        val recorded = log.records.firstOrNull {
            it.first == LogLevel.DEBUG && "reached the end of the pipeline" in it.second
        }
        assertSame(
            transport.refusal,
            recorded?.third,
            "and it is still recorded, carrying what ended the connection: ${log.records}",
        )
    }

    @Test
    fun `a failure that takes every connection with it is not ordinary either`() {
        // The engine's own end is the sibling failure nothing raises yet.
        // How it should reach handlers is settled with the work that starts
        // raising it, so until then it keeps the loud path rather than being
        // quietened by a rule written for a dead peer -- which the end of
        // the pipeline gets right by asking whether this is the instance the
        // transport reported, rather than what type it is.
        val transport = RefusingTransport()
        val log = RecordingLogger()
        val ch = channel(transport, log)
        ch.pipeline.addLast("inbound-only", object : InboundHandler {})

        ch.pipeline.notifyError(EngineFailureException("the loop stopped"))

        assertEquals(
            1,
            log.warnings.count { "Unhandled" in it },
            "a loop that ended on its own is not an ordinary end: ${log.warnings}",
        )
    }

    @Test
    fun `something that failed alongside the connection is not ordinary`() {
        // A refusal carries what could not be finished while it was being
        // contained. The connection ending is routine; a buffer that would
        // not release is not, and it arrives attached to this one instance.
        val transport = RefusingTransport(IllegalStateException("release failed"))
        val log = RecordingLogger()
        val ch = channel(transport, log)
        ch.pipeline.addLast("inbound-only", object : InboundHandler {})

        runCatching { ch.requestFlush() }

        assertEquals(
            1,
            log.warnings.count { "something failed with it" in it },
            "what rode along is named where it arrives, and says why: ${log.warnings}",
        )
    }

    @Test
    fun `a caller that has not read yet is told as quietly as one that has`() {
        // The bridge arrives with the first read, so a channel that only
        // writes has no pipeline to be told through. Its caller is answered
        // by the same wait as a bridged one's, and a connection ending
        // because its peer went away reads the same either way -- the record
        // is there to be found, not to be investigated.
        val transport = RefusingTransport()
        val log = RecordingLogger()
        val ch = channel(transport, log)

        runCatching { ch.requestFlush() }

        assertTrue(
            log.warnings.isEmpty(),
            "nothing here is worth a reader's attention: ${log.warnings}",
        )
        assertTrue(
            log.records.any { it.first == LogLevel.DEBUG },
            "and it is still there to be found: ${log.records}",
        )
    }

    @Test
    fun `a bridged channel is answered by its own API and told quietly`() {
        // Its caller learns the refusal from the suspending wait it already
        // makes, so the reason travelling the pipeline has nobody to inform
        // -- and nothing to complain about either, now that the end of the
        // pipeline knows this send from a bug.
        val transport = RefusingTransport()
        val log = RecordingLogger()
        val ch = channel(transport, log)
        ch.ensureBridge()

        runCatching { ch.requestFlush() }

        assertTrue(
            log.warnings.isEmpty(),
            "nothing here is worth a reader's attention: ${log.warnings}",
        )
        assertTrue(
            log.records.any { it.first == LogLevel.DEBUG && "reached the end of the pipeline" in it.second },
            "and the reason was offered to it, rather than kept from it: ${log.records}",
        )
    }

    @Test
    fun `a replayed report is still the connection's own end`() {
        // A pipeline whose handlers are all outbound journals the report,
        // and a handler attaching later gets it by replay -- arriving at the
        // end of the pipeline long after it was reported. Whose failure it is
        // does not change on the way, so neither does how it is recorded.
        val transport = RefusingTransport()
        val log = RecordingLogger()
        val ch = channel(transport, log)
        ch.pipeline.addLast("outbound-only", object : OutboundHandler {})

        runCatching { ch.requestFlush() }
        ch.pipeline.addLast("late-inbound", object : InboundHandler {})

        assertTrue(
            log.warnings.none { "Unhandled" in it },
            "the replay carries the connection's own end, not a bug: ${log.warnings}",
        )
    }

    @Test
    fun `the rider named at the end of the pipeline is reachable from the warning`() {
        // The loud line there is loud because of what rode along, so the
        // record has to carry it -- a `warn(cause) { }` degraded to
        // `warn { }` reads the same in the message while the leak it names
        // is gone.
        val rider = IllegalStateException("release failed")
        val transport = RefusingTransport(rider)
        val log = RecordingLogger()
        val ch = channel(transport, log)
        ch.pipeline.addLast("inbound-only", object : InboundHandler {})

        runCatching { ch.requestFlush() }

        val carried = log.records.firstOrNull {
            it.first == LogLevel.WARN && "something failed with it" in it.second
        }?.third
        assertSame(transport.refusal, carried, "the warning carries the refusal: ${log.records}")
        assertTrue(
            carried?.suppressedExceptions?.any { it === rider } == true,
            "and the rider rides on it: ${carried?.suppressedExceptions}",
        )
    }

    @Test
    fun `a refusal a handler threw is not the one the transport reported`() {
        // The end of the pipeline tells them apart by identity, because a
        // handler throwing anything is the case it exists to report -- and
        // the type alone cannot say who raised it.
        val transport = RefusingTransport()
        val log = RecordingLogger()
        val ch = channel(transport, log)
        ch.pipeline.addLast(
            "thrower",
            object : InboundHandler {
                override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                    throw RefusedWriteException("hand-made by a handler")
                }
            },
        )

        ch.pipeline.notifyRead("anything")

        assertEquals(
            1,
            log.warnings.count { "Unhandled" in it },
            "a handler that throws is reported, whatever it threw: ${log.warnings}",
        )
    }

    @Test
    fun `a channel with nothing installed keeps nothing to hand a later handler`() {
        // Not offered rather than journalled: a handler that attaches after
        // the connection is over would otherwise be told a reason for an
        // exchange it never took part in.
        val transport = RefusingTransport()
        val ch = channel(transport, RecordingLogger())

        runCatching { ch.requestFlush() }
        val rec = Recorder()
        ch.pipeline.addLast("late", rec)

        assertEquals(emptyList(), rec.seen, "nothing was kept for it")
    }

    @Test
    fun `a rider on a refusal nobody was told about is named in the log`() {
        val rider = IllegalStateException("release failed")
        val transport = RefusingTransport(rider)
        val log = RecordingLogger()
        val ch = channel(transport, log)

        runCatching { ch.requestFlush() }

        assertEquals(
            1,
            log.warnings.count { ENDED_AT_HEAD in it },
            "nothing is installed, so the head is the only reporter: ${log.warnings}",
        )
    }

    @Test
    fun `a refusal no installed handler will receive is recorded as well`() {
        // Handlers, but none that can be told: the replay a journalled cause
        // waits for is scheduled by the first inbound handler, so an
        // outbound-only pipeline never asks for one. The head is the last
        // frame that can record it.
        val transport = RefusingTransport()
        val log = RecordingLogger()
        val ch = channel(transport, log)
        ch.pipeline.addLast("outbound-only", object : OutboundHandler {})

        runCatching { ch.requestFlush() }

        assertEquals(
            1,
            log.records.count { it.first == LogLevel.DEBUG && ENDED_AT_HEAD in it.second },
            "nothing installed can receive it, and it is there to be found: ${log.records}",
        )
    }

    @Test
    fun `the named rider is reachable from the warning and not only from its message`() {
        // A `warn(cause) { ... }` degraded to `warn { ... }` reads the same in
        // the message while the leak it names is gone from the record.
        val rider = IllegalStateException("release failed")
        val transport = RefusingTransport(rider)
        val log = RecordingLogger()
        val ch = channel(transport, log)

        runCatching { ch.requestFlush() }

        val carried = log.causeOf(ENDED_AT_HEAD)
        assertSame(transport.refusal, carried, "the warning carries the refusal itself")
        assertTrue(
            carried?.suppressedExceptions?.any { it === rider } == true,
            "and the rider rides on it: ${carried?.suppressedExceptions}",
        )
    }

    @Test
    fun `an error a handler injects itself does not turn a reported rider into a leak`() {
        val rider = IllegalStateException("release failed")
        val transport = RefusingTransport(rider)
        val log = RecordingLogger()
        val ch = channel(transport, log)
        ch.pipeline.addLast(
            "injector",
            object : DuplexHandler {
                override fun onError(ctx: PipelineHandlerContext, cause: Throwable) {
                    if (cause is RefusedWriteException) {
                        ch.pipeline.notifyError(IllegalStateException("a diagnostic of my own"))
                    }
                }
            },
        )

        runCatching { ch.requestFlush() }

        assertTrue(
            log.warnings.none { ENDED_AT_HEAD in it },
            "the rider arrived attached to the reported refusal: ${log.warnings}",
        )
    }
}
