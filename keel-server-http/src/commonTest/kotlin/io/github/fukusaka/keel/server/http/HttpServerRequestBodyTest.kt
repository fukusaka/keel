package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for the request-body accessors — `receiveBytes`, `receiveChunk` and
 * `receiveChunks` — across bodyless, single-chunk and multi-chunk requests.
 */
internal class HttpServerRequestBodyTest : HttpServerHandlerFixture() {

    @Test
    fun `receiveBytes aggregates the request body`() {
        var received: String? = null
        install(
            Router().apply {
                register(HttpMethod.POST, "/echo") { call ->
                    received = call.receiveBytes().decodeToString()
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedPost("/echo", "hello world")

        assertEquals("hello world", received)
    }

    @Test
    fun `receiveBytes returns an empty array for a bodyless request`() {
        var received: ByteArray? = null
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call ->
                    received = call.receiveBytes()
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedGet("/")

        assertEquals(0, received?.size)
    }

    @Test
    fun `receiveBytes assembles a multi-chunk body in order`() {
        var received: String? = null
        install(
            Router().apply {
                register(HttpMethod.POST, "/echo") { call ->
                    received = call.receiveBytes().decodeToString()
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedPostChunked("/echo", "alpha", "-", "beta", "-", "gamma")

        assertEquals("alpha-beta-gamma", received)
    }

    @Test
    fun `receiveChunk delivers each chunk of a multi-chunk body`() {
        val chunks = mutableListOf<String>()
        install(
            Router().apply {
                register(HttpMethod.POST, "/upload") { call ->
                    while (true) {
                        val chunk = call.receiveChunk() ?: break
                        val bytes = ByteArray(chunk.readableBytes)
                        chunk.readByteArray(bytes, 0, bytes.size)
                        chunks.add(bytes.decodeToString())
                        chunk.release()
                    }
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedPostChunked("/upload", "one", "two", "three")

        assertEquals(listOf("one", "two", "three"), chunks)
    }

    @Test
    fun `receiveChunk streams the body chunks then null`() {
        val chunks = mutableListOf<String>()
        var endReached = false
        install(
            Router().apply {
                register(HttpMethod.POST, "/upload") { call ->
                    while (true) {
                        val chunk = call.receiveChunk() ?: break
                        val bytes = ByteArray(chunk.readableBytes)
                        chunk.readByteArray(bytes, 0, bytes.size)
                        chunks.add(bytes.decodeToString())
                        chunk.release()
                    }
                    endReached = true
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedPost("/upload", "payload")

        assertTrue(endReached, "receiveChunk must return null at end of body")
        assertEquals("payload", chunks.joinToString(""))
    }

    @Test
    fun `receiveChunks hands the whole body off as pooled chunks`() {
        var totalSize = -1
        var body: String? = null
        install(
            Router().apply {
                register(HttpMethod.POST, "/upload") { call ->
                    val chunks = call.receiveChunks()
                    try {
                        totalSize = chunks.totalSize
                        val sb = StringBuilder()
                        chunks.forEach { sb.append(it.readString()) }
                        body = sb.toString()
                    } finally {
                        chunks.release()
                    }
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedPost("/upload", "hello world")

        assertEquals(11, totalSize)
        assertEquals("hello world", body)
    }

    @Test
    fun `receiveChunks returns an empty IoBufChunks for a bodyless request`() {
        var totalSize = -1
        var chunkCount = -1
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call ->
                    val chunks = call.receiveChunks()
                    totalSize = chunks.totalSize
                    chunkCount = chunks.chunkCount
                    chunks.release()
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedGet("/")

        assertEquals(0, totalSize)
        assertEquals(0, chunkCount)
    }

    @Test
    fun `receiveChunks preserves a multi-chunk body as separate pooled chunks`() {
        var chunkCount = -1
        var totalSize = -1
        var body: String? = null
        install(
            Router().apply {
                register(HttpMethod.POST, "/upload") { call ->
                    val chunks = call.receiveChunks()
                    try {
                        chunkCount = chunks.chunkCount
                        totalSize = chunks.totalSize
                        val sb = StringBuilder()
                        chunks.forEach { sb.append(it.readString()) }
                        body = sb.toString()
                    } finally {
                        chunks.release()
                    }
                    call.respond(HttpResponse.ok("ok"))
                }
            },
        )

        feedPostChunked("/upload", "alpha", "-", "beta", "-", "gamma")

        assertEquals(5, chunkCount)
        assertEquals(16, totalSize)
        assertEquals("alpha-beta-gamma", body)
    }
}
