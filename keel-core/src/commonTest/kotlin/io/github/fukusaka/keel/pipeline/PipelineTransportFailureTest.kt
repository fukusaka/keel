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
    fun `a rider on a refusal nobody was told about is named in the log`() {
        val rider = IllegalStateException("release failed")
        val transport = RefusingTransport(rider)
        val log = RecordingLogger()
        val ch = channel(transport, log)

        runCatching { ch.requestFlush() }

        assertEquals(
            1,
            log.warnings.count { "cleanup did not finish" in it },
            "nothing is installed, so the head is the only reporter: ${log.warnings}",
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

        val carried = log.causeOf("cleanup did not finish")
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
            log.warnings.none { "cleanup did not finish" in it },
            "the rider arrived attached to the reported refusal: ${log.warnings}",
        )
    }
}
