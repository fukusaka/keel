package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for method and predicate routing, `Accept`-driven content negotiation,
 * and the `Vary` header it produces.
 */
internal class HttpServerContentNegotiationTest : HttpServerHandlerFixture() {

    @Test
    fun `a wrong-method request to a registered path is answered with 405 and an Allow header`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/users") { call -> call.respond(HttpResponse.ok("ok")) }
                register(HttpMethod.POST, "/users") { call -> call.respond(HttpResponse.ok("ok")) }
            },
        )

        feedMethod("DELETE", "/users")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 405"), "expected 405: $text")
        // The Allow header lists the registered methods, sorted, comma-space joined.
        assertTrue(text.contains("Allow: GET, POST"), "expected Allow header: $text")
    }

    @Test
    fun `a predicate-routed request reaches the matching handler`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", header("X-Format", "json")) { call ->
                    call.respond(HttpResponse.ok("json-body"))
                }
                register(HttpMethod.GET, "/data", header("X-Format", "xml")) { call ->
                    call.respond(HttpResponse.ok("xml-body"))
                }
            },
        )

        feedWithFormat("GET", "/data", "xml")

        assertTrue(responseText().endsWith("xml-body"), "expected the xml handler: ${responseText()}")
    }

    @Test
    fun `content negotiation dispatches to the handler whose produces type the Accept header names`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    call.respond(HttpResponse.ok("json-body"))
                }
                register(HttpMethod.GET, "/data", produces = listOf("application/xml")) { call ->
                    call.respond(HttpResponse.ok("xml-body"))
                }
            },
        )

        feedWithAccept("/data", "application/xml")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "status line: $text")
        assertTrue(text.endsWith("xml-body"), "expected the xml handler: $text")
    }

    @Test
    fun `content negotiation reads media-ranges split across multiple Accept lines`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    call.respond(HttpResponse.ok("json-body"))
                }
                register(HttpMethod.GET, "/data", produces = listOf("application/xml")) { call ->
                    call.respond(HttpResponse.ok("xml-body"))
                }
            },
        )

        // Two Accept lines: json at q=0.4 (first line), xml at q=0.9 (second).
        // If only the first line were read, xml would be unacceptable and json
        // would win; reading both (RFC 9110 §5.3) makes xml the best match.
        feedWithTwoAccepts("/data", "application/json;q=0.4", "application/xml;q=0.9")

        assertTrue(responseText().endsWith("xml-body"), "second Accept line must be honoured: ${responseText()}")
    }

    @Test
    fun `content negotiation answers 406 when no produced type is acceptable`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    call.respond(HttpResponse.ok("json-body"))
                }
                register(HttpMethod.GET, "/data", produces = listOf("application/xml")) { call ->
                    call.respond(HttpResponse.ok("xml-body"))
                }
            },
        )

        feedWithAccept("/data", "text/plain")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 406"), "expected 406: $text")
        // The body lists the producible types so the client can renegotiate.
        assertTrue(text.contains("application/json"), "producible json: $text")
        assertTrue(text.contains("application/xml"), "producible xml: $text")
        // A 406 is an Accept-negotiation outcome, so it carries Vary: Accept.
        assertTrue(text.contains("vary: accept", ignoreCase = true), "expected Vary: Accept: $text")
    }

    @Test
    fun `a content-negotiated response carries Vary Accept`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    call.respond(HttpResponse.ok("json-body"))
                }
                register(HttpMethod.GET, "/data", produces = listOf("application/xml")) { call ->
                    call.respond(HttpResponse.ok("xml-body"))
                }
            },
        )

        feedWithAccept("/data", "application/json")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "status line: $text")
        assertTrue(text.contains("vary: accept", ignoreCase = true), "expected Vary: Accept: $text")
    }

    @Test
    fun `a content-negotiated response carries Vary Accept even with no Accept header`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    call.respond(HttpResponse.ok("json-body"))
                }
            },
        )

        // No Accept header: produces is ignored for selection, but the
        // resource still varies on Accept, so the response advertises it.
        feedGet("/data")

        assertTrue(
            responseText().contains("vary: accept", ignoreCase = true),
            "expected Vary: Accept: ${responseText()}",
        )
    }

    @Test
    fun `a non-negotiated response does not carry Vary Accept`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/plain") { call -> call.respond(HttpResponse.ok("ok")) }
            },
        )

        feedGet("/plain")

        assertFalse(
            responseText().contains("vary: accept", ignoreCase = true),
            "unexpected Vary: Accept: ${responseText()}",
        )
    }

    @Test
    fun `Vary Accept is appended alongside a Vary the handler already set`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    val base = HttpResponse.of(HttpStatus.OK, "json-body")
                    val withVary = base.copy(
                        headers = HttpHeaders.build {
                            base.headers.forEach { name, value -> add(name, value) }
                            set(HttpHeaderName.VARY, "Accept-Encoding")
                        },
                    )
                    call.respond(withVary)
                }
            },
        )

        feedWithAccept("/data", "application/json")

        // `responseText()` drains the written buffers, so capture it once.
        val text = responseText()
        // Keel appends rather than rewriting: the handler's Vary line stays
        // byte-for-byte, and Accept is added (here as a separate line).
        assertTrue(
            text.lineSequence().any { it.trimEnd().equals("Vary: Accept-Encoding", ignoreCase = true) },
            "handler's Vary line preserved verbatim: $text",
        )
        val tokens = varyTokensOf(text)
        assertTrue(tokens.any { it.equals("Accept-Encoding", ignoreCase = true) }, "keeps Accept-Encoding: $tokens")
        assertTrue(tokens.any { it.equals("Accept", ignoreCase = true) }, "adds Accept: $tokens")
    }

    @Test
    fun `Vary Accept is appended without dropping multiple handler Vary lines`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    val base = HttpResponse.of(HttpStatus.OK, "json-body")
                    // Two distinct Vary lines — list-based field, equivalent
                    // to one comma-joined line (RFC 9110 §5.3 / §12.5.5).
                    val withVary = base.copy(
                        headers = HttpHeaders.build {
                            base.headers.forEach { name, value -> add(name, value) }
                            add(HttpHeaderName.VARY, "Accept-Encoding")
                            add(HttpHeaderName.VARY, "Cookie")
                        },
                    )
                    call.respond(withVary)
                }
            },
        )

        feedWithAccept("/data", "application/json")

        // No field-name dropped: both handler lines survive and Accept is added.
        val tokens = varyTokensOf(responseText())
        assertTrue(tokens.any { it.equals("Accept-Encoding", ignoreCase = true) }, "keeps Accept-Encoding: $tokens")
        assertTrue(tokens.any { it.equals("Cookie", ignoreCase = true) }, "keeps Cookie: $tokens")
        assertTrue(tokens.any { it.equals("Accept", ignoreCase = true) }, "adds Accept: $tokens")
    }

    @Test
    fun `Vary star is left untouched since it already subsumes Accept`() {
        install(
            Router().apply {
                register(HttpMethod.GET, "/data", produces = listOf("application/json")) { call ->
                    val base = HttpResponse.of(HttpStatus.OK, "json-body")
                    val withVary = base.copy(
                        headers = HttpHeaders.build {
                            base.headers.forEach { name, value -> add(name, value) }
                            set(HttpHeaderName.VARY, "*")
                        },
                    )
                    call.respond(withVary)
                }
            },
        )

        feedWithAccept("/data", "application/json")

        val vary = responseText().lineSequence()
            .firstOrNull { it.startsWith("Vary:", ignoreCase = true) }
            ?: error("no Vary header: ${responseText()}")
        assertEquals("Vary: *", vary.trimEnd(), "`*` subsumes Accept, so it is left as-is: $vary")
    }
}
