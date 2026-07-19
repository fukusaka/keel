package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.client.http.dsl.keelHttpClient
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpRequest
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.addHttp1ServerCodec
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import io.github.fukusaka.keel.testing.engine.InMemoryEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Client-wide `defaultHeaders { }`: every request carries them, a per-request
 * header of the same name replaces (does not duplicate) the default, and the
 * auto-filled `Host` / `Content-Length` respect a default that sets them.
 *
 * The server captures what actually arrived on the wire, so these assert the
 * emitted request rather than the client's internal state.
 */
class DefaultHeadersTest {

    private val budget = 5.seconds

    /** Captures the headers of the last request the server received. */
    private class Captured {
        var headers: List<Pair<String, String>> = emptyList()
        fun valuesOf(name: String) = headers.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }
        fun valueOf(name: String) = valuesOf(name).singleOrNull()
    }

    private class CapturingHandler(private val captured: Captured) : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is HttpRequest) {
                val pairs = mutableListOf<Pair<String, String>>()
                msg.headers.forEach { name, value -> pairs.add(name to value) }
                captured.headers = pairs
                ctx.propagateWriteAndFlush(HttpResponse.of(HttpStatus.OK, "ok"))
            } else {
                ctx.propagateRead(msg)
            }
        }
    }

    private fun startServer(engine: InMemoryEngine, captured: Captured): PipelinedStreamServer =
        engine.bindPipeline(InetSocketAddress("127.0.0.1", 0), BindConfig()) { channel ->
            channel.addHttp1ServerCodec()
            channel.pipeline.addLast("capture", CapturingHandler(captured))
        }

    private fun urlOf(server: PipelinedStreamServer): String {
        val addr = server.localAddress as InetSocketAddress
        return "http://${addr.hostString}:${addr.port}/"
    }

    @Test
    fun `default headers are sent with every request`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val captured = Captured()
        val server = startServer(engine, captured)
        val client = keelHttpClient(engine) {
            defaultHeaders {
                add("User-Agent", "keel-test/1.0")
                add("Accept", "application/json")
            }
        }
        try {
            repeat(2) { assertEquals(HttpStatus.OK, client.get(urlOf(server)).status) }
            assertEquals("keel-test/1.0", captured.valueOf("User-Agent"))
            assertEquals("application/json", captured.valueOf("Accept"))
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `a per-request header replaces the default rather than duplicating it`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val captured = Captured()
        val server = startServer(engine, captured)
        val client = keelHttpClient(engine) {
            defaultHeaders { add("User-Agent", "default-agent") }
        }
        try {
            val perRequest = HttpHeaders().apply { add("User-Agent", "per-request-agent") }
            assertEquals(HttpStatus.OK, client.get(urlOf(server), perRequest).status)
            assertEquals(
                listOf("per-request-agent"),
                captured.valuesOf("User-Agent"),
                "the caller's value must win outright, not be appended to the default",
            )
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `a default Host is honoured instead of being auto-filled from the URL`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val captured = Captured()
        val server = startServer(engine, captured)
        val client = keelHttpClient(engine) {
            defaultHeaders { add("Host", "example.test") }
        }
        try {
            assertEquals(HttpStatus.OK, client.get(urlOf(server)).status)
            assertEquals(
                listOf("example.test"),
                captured.valuesOf("Host"),
                "the auto-filled Host must not be added alongside a default Host",
            )
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `no default headers leaves the request as before`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val captured = Captured()
        val server = startServer(engine, captured)
        val client = keelHttpClient(engine)
        try {
            assertEquals(HttpStatus.OK, client.get(urlOf(server)).status)
            assertNull(captured.valueOf("User-Agent"), "nothing is invented when no defaults are configured")
            val addr = server.localAddress as InetSocketAddress
            assertEquals(
                listOf("${addr.hostString}:${addr.port}"),
                captured.valuesOf("Host"),
                "Host is still auto-filled from the URL authority",
            )
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }
}
