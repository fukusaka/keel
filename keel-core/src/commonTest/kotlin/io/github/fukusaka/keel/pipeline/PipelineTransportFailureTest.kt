package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.core.RefusedWriteException
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

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
    fun `the suspending bridge takes the reason it hands to its receiver`() {
        // It closes the receiving channel with the cause, which is the whole
        // of what it can do about it, so there is nothing left to pass on --
        // and it is the last handler in the pipelines that install it.
        val transport = RefusingTransport()
        val log = RecordingLogger()
        val ch = channel(transport, log)
        ch.pipeline.addLast("bridge", SuspendMessageBridge(String::class))

        runCatching { ch.requestFlush() }

        assertTrue(
            log.warnings.none { "Unhandled" in it },
            "the receiver is being told, so this is not unhandled: ${log.warnings}",
        )
    }

    @Test
    fun `a handler that does not act on the reason lets it reach the tail`() {
        // The reason is delivered as a pipeline error, so a pipeline whose
        // handlers neither handle nor stop it ends at the tail, which says
        // so. That is the pipeline contract rather than a special case for
        // this failure: an application that wants the connection's end to be
        // its own business overrides `onError`, and keel's own bridges do.
        val transport = RefusingTransport()
        val log = RecordingLogger()
        val ch = channel(transport, log)
        ch.pipeline.addLast("inbound-only", object : InboundHandler {})

        runCatching { ch.requestFlush() }

        assertEquals(
            1,
            log.warnings.count { "Unhandled" in it },
            "nobody acted on it, and the tail says which: ${log.warnings}",
        )
    }

    @Test
    fun `a bridged channel is left to answer through its own API`() {
        val transport = RefusingTransport()
        val log = RecordingLogger()
        val ch = channel(transport, log)
        ch.ensureBridge()

        runCatching { ch.requestFlush() }

        assertTrue(
            log.warnings.none { "Unhandled" in it },
            "the suspending caller is the one being answered: ${log.warnings}",
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
            log.warnings.count { "contained without reaching" in it },
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
            log.warnings.count { "contained without reaching" in it },
            "nothing installed can receive it: ${log.warnings}",
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

        val carried = log.causeOf("contained without reaching")
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
            log.warnings.none { "contained without reaching" in it },
            "the rider arrived attached to the reported refusal: ${log.warnings}",
        )
    }
}
