package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.core.RefusedWriteException
import io.github.fukusaka.keel.logging.LogLevel
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.testing.InjectedFault
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins what the server's own handler does with a failure the transport
 * reports — now that a transport reports a refused send to the pipeline.
 */
internal class HttpServerConnectionFailureTest {

    private class RecordingLogger : Logger {
        val records = mutableListOf<Pair<LogLevel, String>>()
        val warnings: List<String> get() = records.filter { it.first == LogLevel.WARN }.map { it.second }
        override fun isLoggable(level: LogLevel) = true
        override fun rawLog(level: LogLevel, throwable: Throwable?, message: Any?) {
            records.add(level to message.toString())
        }
    }

    private val scope = CoroutineScope(Dispatchers.Unconfined)
    private val transport = TestIoTransport()
    private val log = RecordingLogger()
    private val channel = object : AbstractPipelinedChannel(transport, log) {}

    @AfterTest
    fun tearDown() {
        transport.close()
    }

    private fun install() {
        channel.installHttpServerPipeline(
            Router(),
            emptyList(),
            ErrorHandlers.DEFAULT,
            QueryParameterConfig.DEFAULT,
            scope,
        )
    }

    @Test
    fun `the connection's own failure ends here rather than reaching the tail`() {
        // The end is what this handler acts on, and it is the last handler
        // the server installs -- so passing the reason on would report an
        // ordinary dead peer at the tail, which records what arrives there
        // as an application bug.
        install()

        channel.pipeline.notifyError(RefusedWriteException("peer is gone"))

        assertTrue(
            log.warnings.none { "Unhandled" in it },
            "the connection ending is this handler's business: ${log.warnings}",
        )
    }

    @Test
    fun `an error the connection did not cause keeps travelling`() {
        // Absorbing everything would silence what the tail exists to report.
        install()

        channel.pipeline.notifyError(InjectedFault("a decoder gave up"))

        assertTrue(
            log.warnings.any { "Unhandled" in it },
            "nobody handled it, and the tail says so: ${log.warnings}",
        )
    }
}
