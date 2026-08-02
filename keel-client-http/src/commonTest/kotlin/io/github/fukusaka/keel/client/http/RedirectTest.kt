package io.github.fukusaka.keel.client.http

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.client.http.dsl.keelHttpClient
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpRequest
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpStatus
import io.github.fukusaka.keel.codec.http.addHttp1ServerCodec
import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.PipelinedStreamServer
import io.github.fukusaka.keel.testing.engine.InMemoryEngine
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/**
 * Redirect following (RFC 9110 §15.4). The server records every request it
 * receives, so these assert the *sequence the client actually put on the wire*
 * — method, path, and headers per hop — not just the final response.
 */
class RedirectTest {

    private val budget = 5.seconds

    /** One received request, as the server saw it. */
    private class Received(val method: String, val target: String, val headers: List<Pair<String, String>>) {
        fun header(name: String) = headers.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second
    }

    private class Log {
        val requests = mutableListOf<Received>()
        val methods get() = requests.map { it.method }
        val targets get() = requests.map { it.target }
    }

    /**
     * Answers each request from [route]: a `status to location` pair redirects,
     * `null` serves a 200. Unknown paths get a 200 too, so a chain ends anywhere.
     */
    private class RedirectHandler(
        private val log: Log,
        private val route: (String) -> Pair<HttpStatus, String>?,
    ) : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg !is HttpRequest) {
                ctx.propagateRead(msg)
                return
            }
            val pairs = mutableListOf<Pair<String, String>>()
            msg.headers.forEach { name, value -> pairs.add(name to value) }
            log.requests.add(Received(msg.method.name, msg.uri, pairs))

            val redirect = route(msg.uri)
            val response = if (redirect == null) {
                HttpResponse.of(HttpStatus.OK, "done")
            } else {
                HttpResponse.of(redirect.first, "").also { it.headers.add("Location", redirect.second) }
            }
            ctx.propagateWriteAndFlush(response)
        }
    }

    private fun startServer(
        engine: InMemoryEngine,
        log: Log,
        route: (String) -> Pair<HttpStatus, String>?,
    ): PipelinedStreamServer =
        engine.bindPipeline(InetSocketAddress("127.0.0.1", 0), BindConfig()) { channel ->
            channel.addHttp1ServerCodec()
            channel.pipeline.addLast("redirect", RedirectHandler(log, route))
        }

    private fun urlOf(server: PipelinedStreamServer, path: String): String {
        val addr = server.localAddress as InetSocketAddress
        return "http://${addr.hostString}:${addr.port}$path"
    }

    @Test
    fun `a relative Location is followed to the resolved path`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val log = Log()
        val server = startServer(engine, log) { path ->
            if (path == "/dir/start") HttpStatus.FOUND to "next" else null
        }
        val client = keelHttpClient(engine)
        try {
            assertEquals(HttpStatus.OK, client.get(urlOf(server, "/dir/start")).status)
            assertEquals(listOf("/dir/start", "/dir/next"), log.targets, "relative Location resolves onto the base dir")
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `303 redirects a POST to a GET without the body`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val log = Log()
        val server = startServer(engine, log) { path ->
            if (path == "/submit") HttpStatus.SEE_OTHER to "/result" else null
        }
        val client = keelHttpClient(engine)
        try {
            val res = client.post(urlOf(server, "/submit"), body = "payload".encodeToByteArray())
            assertEquals(HttpStatus.OK, res.status)
            assertEquals(listOf("POST", "GET"), log.methods, "303 must redirect to GET")
            assertNull(log.requests[1].header("Content-Length"), "the dropped body must not leave a Content-Length")
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `307 preserves the method and body`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val log = Log()
        val server = startServer(engine, log) { path ->
            if (path == "/submit") HttpStatus.TEMPORARY_REDIRECT to "/elsewhere" else null
        }
        val client = keelHttpClient(engine)
        try {
            assertEquals(
                HttpStatus.OK,
                client.post(urlOf(server, "/submit"), body = "payload".encodeToByteArray()).status,
            )
            assertEquals(listOf("POST", "POST"), log.methods, "307 exists to preserve the method")
            assertEquals("7", log.requests[1].header("Content-Length"), "the body is re-sent")
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `302 rewrites POST to GET but leaves GET alone`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val log = Log()
        val server = startServer(engine, log) { path ->
            if (path == "/a") HttpStatus.FOUND to "/b" else null
        }
        val client = keelHttpClient(engine)
        try {
            client.post(urlOf(server, "/a"), body = "x".encodeToByteArray())
            assertEquals(listOf("POST", "GET"), log.methods, "302 rewrites POST to GET")
            log.requests.clear()
            client.put(urlOf(server, "/a"), body = "x".encodeToByteArray())
            assertEquals(listOf("PUT", "PUT"), log.methods, "302 leaves a non-POST method alone")
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `Authorization is dropped when a hop crosses origin but kept within it`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val sameOriginLog = Log()
        val otherLog = Log()
        val other = startServer(engine, otherLog) { null }
        val otherAddr = other.localAddress as InetSocketAddress
        val server = startServer(engine, sameOriginLog) { path ->
            when (path) {
                "/stay" -> HttpStatus.FOUND to "/within"
                "/leave" -> HttpStatus.FOUND to "http://${otherAddr.hostString}:${otherAddr.port}/landed"
                else -> null
            }
        }
        val client = keelHttpClient(engine)
        try {
            val auth = HttpHeaders().apply { add("Authorization", "Bearer secret") }

            client.get(urlOf(server, "/stay"), auth)
            assertEquals("Bearer secret", sameOriginLog.requests[1].header("Authorization"), "same origin keeps it")

            sameOriginLog.requests.clear()
            client.get(urlOf(server, "/leave"), auth)
            assertEquals("Bearer secret", sameOriginLog.requests[0].header("Authorization"), "sent to the first origin")
            assertNull(otherLog.requests[0].header("Authorization"), "credentials must not cross origin")
        } finally {
            client.close()
            other.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `a caller-supplied Host does not follow to another origin`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val firstLog = Log()
        val otherLog = Log()
        val other = startServer(engine, otherLog) { null }
        val otherAddr = other.localAddress as InetSocketAddress
        val server = startServer(engine, firstLog) { path ->
            if (path == "/leave") {
                HttpStatus.FOUND to "http://${otherAddr.hostString}:${otherAddr.port}/landed"
            } else {
                null
            }
        }
        val client = keelHttpClient(engine)
        try {
            // An explicit Host addresses a virtual host on the *first* origin; it
            // must not name that host to the origin the redirect points at.
            val vhost = HttpHeaders().apply { add("Host", "app.example") }
            client.get(urlOf(server, "/leave"), vhost)

            assertEquals("app.example", firstLog.requests[0].header("Host"), "honoured on the origin it was for")
            assertEquals(
                "${otherAddr.hostString}:${otherAddr.port}",
                otherLog.requests[0].header("Host"),
                "the new origin gets its own Host, not the previous one's",
            )
        } finally {
            client.close()
            other.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `a redirect cycle fails once the cap is spent`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val log = Log()
        val server = startServer(engine, log) { path ->
            when (path) {
                "/a" -> HttpStatus.FOUND to "/b"
                "/b" -> HttpStatus.FOUND to "/a"
                else -> null
            }
        }
        val client = keelHttpClient(engine) { maxRedirects = 3 }
        try {
            assertFailsWith<TooManyRedirectsException> { client.get(urlOf(server, "/a")) }
            assertEquals(4, log.requests.size, "the original request plus maxRedirects hops")
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `followRedirects false returns the 3xx itself`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val log = Log()
        val server = startServer(engine, log) { path ->
            if (path == "/a") HttpStatus.FOUND to "/b" else null
        }
        val client = keelHttpClient(engine) { followRedirects = false }
        try {
            val res = client.get(urlOf(server, "/a"))
            assertEquals(HttpStatus.FOUND, res.status, "the redirect is handed back untouched")
            assertEquals("/b", res.headers.getString("Location"))
            assertEquals(1, log.requests.size, "no second request is made")
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `a 3xx without a Location is returned as-is`() = runTest(timeout = budget) {
        val engine = InMemoryEngine()
        val log = Log()
        val server = engine.bindPipeline(InetSocketAddress("127.0.0.1", 0), BindConfig()) { channel ->
            channel.addHttp1ServerCodec()
            channel.pipeline.addLast(
                "no-location",
                object : InboundHandler {
                    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
                        if (msg is HttpRequest) {
                            log.requests.add(Received(msg.method.name, msg.uri, emptyList()))
                            ctx.propagateWriteAndFlush(HttpResponse.of(HttpStatus.FOUND, "no location here"))
                        } else {
                            ctx.propagateRead(msg)
                        }
                    }
                },
            )
        }
        val client = keelHttpClient(engine)
        try {
            assertEquals(HttpStatus.FOUND, client.get(urlOf(server, "/a")).status)
            assertEquals(1, log.requests.size, "nothing to follow means no extra request")
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `a followed redirect leaks no pooled buffers`() = runTest(timeout = budget) {
        val tracking = TrackingAllocator()
        val engine = InMemoryEngine(IoEngineConfig(allocator = tracking))
        val log = Log()
        val server = startServer(engine, log) { path ->
            if (path == "/a") HttpStatus.FOUND to "/b" else null
        }
        val client = keelHttpClient(engine)
        try {
            assertEquals(HttpStatus.OK, client.get(urlOf(server, "/a")).status)
        } finally {
            client.close()
            server.close()
            engine.close()
        }
        assertEquals(0, tracking.outstandingCount, "the redirect chain leaked pooled buffers")
    }
}
