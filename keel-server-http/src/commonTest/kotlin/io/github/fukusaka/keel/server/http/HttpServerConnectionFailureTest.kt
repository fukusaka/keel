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
    fun `an ordinary dead peer is not reported as a bug in the server`() {
        // Nothing in this stack acts on the reason -- the end is what the
        // server handler acts on, and it cancels the in-flight call and
        // leaves the registry whatever the reason was. So the reason
        // reaches the end of the pipeline, which records it as what it is.
        install()

        channel.pipeline.notifyError(RefusedWriteException("peer is gone"))

        assertTrue(
            log.warnings.none { "Unhandled" in it },
            "a peer disappearing mid-write is not this server's bug: ${log.warnings}",
        )
    }

    @Test
    fun `an error the connection did not cause is still reported as one`() {
        // Recording every failure quietly would silence what the end of the
        // pipeline exists to report.
        install()

        channel.pipeline.notifyError(InjectedFault("a decoder gave up"))

        assertTrue(
            log.warnings.any { "Unhandled" in it },
            "nobody handled it, and the tail says so: ${log.warnings}",
        )
    }
}
