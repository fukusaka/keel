package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Tests for the error paths — a custom `notFound`, the exception mappers and
 * their ordering — and for connection draining and the registry shard.
 */
internal class HttpServerErrorHandlingTest : HttpServerHandlerFixture() {

    @Test
    fun `a custom notFound handler replaces the default 404`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/known") { call -> call.respond(HttpResponse.ok("ok")) }
            },
            errorHandlers = ErrorHandlers(
                notFound = { call -> call.respondText("custom not found", HttpStatus.NOT_FOUND) },
                exceptionMappers = emptyList(),
            ),
        )

        feedGet("/missing")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 404"), "status: $text")
        assertTrue(text.endsWith("custom not found"), "body: $text")
    }

    @Test
    fun `an exception mapper turns a thrown exception into a response`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { throw IllegalArgumentException("bad input") }
            },
            errorHandlers = ErrorHandlers(
                notFound = null,
                exceptionMappers = listOf(
                    ExceptionMapper(IllegalArgumentException::class) { call, cause ->
                        call.respondText("mapped: ${cause.message}", HttpStatus.BAD_REQUEST)
                    },
                ),
            ),
        )

        feedGet("/")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 400"), "status: $text")
        assertTrue(text.endsWith("mapped: bad input"), "body: $text")
    }

    @Test
    fun `exception mappers are matched in registration order`() {
        val hit = mutableListOf<String>()
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { error("boom") }
            },
            errorHandlers = ErrorHandlers(
                notFound = null,
                exceptionMappers = listOf(
                    ExceptionMapper(IllegalStateException::class) { call, _ ->
                        hit.add("specific")
                        call.respond(HttpResponse.ok("specific"))
                    },
                    ExceptionMapper(RuntimeException::class) { call, _ ->
                        hit.add("general")
                        call.respond(HttpResponse.ok("general"))
                    },
                ),
            ),
        )

        feedGet("/")

        assertEquals(listOf("specific"), hit)
    }

    @Test
    fun `an unmapped exception falls back to the default 500`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { throw IllegalStateException("boom") }
            },
            errorHandlers = ErrorHandlers(
                notFound = null,
                exceptionMappers = listOf(
                    ExceptionMapper(IllegalArgumentException::class) { call, _ ->
                        call.respond(HttpResponse.ok("should not run"))
                    },
                ),
            ),
        )

        feedGet("/")

        assertTrue(responseText().startsWith("HTTP/1.1 500"), "expected 500 fallback: ${responseText()}")
    }

    @Test
    fun `an exception after a response is not handled by a mapper`() {
        var mapperRan = false
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call ->
                    call.respond(HttpResponse.ok("partial"))
                    throw IllegalArgumentException("late")
                }
            },
            errorHandlers = ErrorHandlers(
                notFound = null,
                exceptionMappers = listOf(
                    ExceptionMapper(IllegalArgumentException::class) { _, _ -> mapperRan = true },
                ),
            ),
        )

        feedGet("/")

        assertTrue(!mapperRan, "mapper must not run once the handler has responded")
        assertTrue(responseText().endsWith("partial"), "the first response stands: ${responseText()}")
    }

    @Test
    fun `draining an idle connection closes the channel`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call -> call.respond(HttpResponse.ok("ok")) }
            },
        )

        handler().requestDrain()

        assertTrue(transport.closed, "an idle connection should be closed at once by drain")
    }

    @Test
    fun `draining a connection mid-request tags the response Connection close then closes`() {
        val gate = CompletableDeferred<Unit>()
        install(
            Router().apply {
                register(HttpMethod.GET, "/slow") { call ->
                    gate.await()
                    call.respond(HttpResponse.ok("done"))
                }
            },
        )

        feedGet("/slow")
        assertFalse(transport.closed, "the connection stays open while the request is in flight")

        handler().requestDrain()
        // The in-flight handler is still suspended — drain must not close it yet.
        assertFalse(transport.closed, "an active connection is not closed until its request finishes")

        gate.complete(Unit)

        assertTrue(transport.closed, "the connection closes once the in-flight request has responded")
        val response = responseAtClose ?: error("no response captured at close")
        assertTrue(response.startsWith("HTTP/1.1 200"), "status line: $response")
        assertTrue(
            response.contains("connection: close", ignoreCase = true),
            "the response should carry `Connection: close`: $response",
        )
    }

    @Test
    fun `requestDrain on an already drained connection is a no-op`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/") { call -> call.respond(HttpResponse.ok("ok")) }
            },
        )

        // Held by reference, as the server's registry holds it: once the
        // channel's close has ended the pipeline's life, the handler is no
        // longer in the pipeline to be looked up.
        val h = handler()
        h.requestDrain()
        // A second drain after the channel is already closed must not throw.
        h.requestDrain()

        assertTrue(transport.closed)
    }

    @Test
    fun `a connection joins the registry shard on activation and leaves on inactivation`() = runTest(
        timeout = 15.seconds,
    ) {
        val connections = ServerConnections()
        channel.installHttpServerPipeline(
            Router(),
            emptyList(),
            ErrorHandlers.DEFAULT,
            QueryParameterConfig.DEFAULT,
            scope,
            connections,
        )

        channel.pipeline.notifyActive()
        assertEquals(1, connections.snapshot().size, "the connection joins its shard on onActive")

        channel.pipeline.notifyInactive()
        assertEquals(0, connections.snapshot().size, "the connection leaves its shard on onInactive")
    }
}
