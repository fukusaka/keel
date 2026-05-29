package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Unit tests for the [Router]'s `produces` content negotiation (router
 * R-5, design.md §38.9.9): q-value best-match selection across handlers
 * sharing a method × path, and the `406 Not Acceptable` resolution.
 * Pure synchronous logic — no timeout needed.
 */
class RouterContentNegotiationTest {

    private fun headFor(method: HttpMethod, path: String, accept: String? = null): HttpRequestHead {
        val headers = if (accept != null) HttpHeaders.of("Accept" to accept) else HttpHeaders()
        return HttpRequestHead(method = method, uri = path, headers = headers)
    }

    private fun Router.resolveGet(path: String, accept: String? = null): RouteResolution =
        resolve(HttpMethod.GET, path, headFor(HttpMethod.GET, path, accept))

    private fun Router.handlerFor(path: String, accept: String? = null): RouteHandler? =
        (resolveGet(path, accept) as? RouteResolution.Matched)?.match?.handler

    @Test
    fun `produces selects the handler whose type the Accept header names`() {
        val json: RouteHandler = {}
        val xml: RouteHandler = {}
        val router = Router()
        router.register(HttpMethod.GET, "/data", produces = listOf("application/json"), handler = json)
        router.register(HttpMethod.GET, "/data", produces = listOf("application/xml"), handler = xml)

        assertSame(json, router.handlerFor("/data", "application/json"))
        assertSame(xml, router.handlerFor("/data", "application/xml"))
    }

    @Test
    fun `q-value picks the most preferred producible type`() {
        val json: RouteHandler = {}
        val xml: RouteHandler = {}
        val router = Router()
        router.register(HttpMethod.GET, "/data", produces = listOf("application/json"), handler = json)
        router.register(HttpMethod.GET, "/data", produces = listOf("application/xml"), handler = xml)

        // Client prefers xml (q=0.9) over json (q=0.4) despite json being registered first.
        assertSame(xml, router.handlerFor("/data", "application/json;q=0.4, application/xml;q=0.9"))
        // And the reverse.
        assertSame(json, router.handlerFor("/data", "application/json;q=0.9, application/xml;q=0.4"))
    }

    @Test
    fun `a wildcard Accept matches any produced type and the first registered wins the tie`() {
        val json: RouteHandler = {}
        val xml: RouteHandler = {}
        val router = Router()
        router.register(HttpMethod.GET, "/data", produces = listOf("application/json"), handler = json)
        router.register(HttpMethod.GET, "/data", produces = listOf("application/xml"), handler = xml)

        // */* matches both at equal score; the earliest registration wins.
        assertSame(json, router.handlerFor("/data", "*/*"))
    }

    @Test
    fun `absent Accept ignores produces and takes the first registered handler`() {
        val json: RouteHandler = {}
        val xml: RouteHandler = {}
        val router = Router()
        router.register(HttpMethod.GET, "/data", produces = listOf("application/json"), handler = json)
        router.register(HttpMethod.GET, "/data", produces = listOf("application/xml"), handler = xml)

        assertSame(json, router.handlerFor("/data", accept = null))
    }

    @Test
    fun `406 Not Acceptable when every producible type is refused`() {
        val json: RouteHandler = {}
        val xml: RouteHandler = {}
        val router = Router()
        router.register(HttpMethod.GET, "/data", produces = listOf("application/json"), handler = json)
        router.register(HttpMethod.GET, "/data", produces = listOf("application/xml"), handler = xml)

        val resolution = router.resolveGet("/data", "text/plain")
        val notAcceptable = assertIs<RouteResolution.NotAcceptable>(resolution)
        assertTrue("application/json" in notAcceptable.producibleTypes)
        assertTrue("application/xml" in notAcceptable.producibleTypes)
    }

    @Test
    fun `q equals zero on the only producible type yields 406`() {
        val json: RouteHandler = {}
        val router = Router()
        router.register(HttpMethod.GET, "/data", produces = listOf("application/json"), handler = json)

        assertIs<RouteResolution.NotAcceptable>(router.resolveGet("/data", "application/json;q=0"))
    }

    @Test
    fun `a no-produces catch-all serves any Accept and prevents 406`() {
        val json: RouteHandler = {}
        val anyType: RouteHandler = {}
        val router = Router()
        router.register(HttpMethod.GET, "/data", produces = listOf("application/json"), handler = json)
        router.register(HttpMethod.GET, "/data", handler = anyType) // catch-all (no produces)

        // json is preferred when accepted...
        assertSame(json, router.handlerFor("/data", "application/json"))
        // ...but an otherwise-unacceptable Accept falls back to the catch-all, not 406.
        assertSame(anyType, router.handlerFor("/data", "text/plain"))
    }

    @Test
    fun `405 still wins over 406 when the method itself is unregistered`() {
        val json: RouteHandler = {}
        val router = Router()
        router.register(HttpMethod.GET, "/data", produces = listOf("application/json"), handler = json)

        // POST is not registered at all → 405 (not 406), even with a refusing Accept.
        val resolution = router.resolve(HttpMethod.POST, "/data", headFor(HttpMethod.POST, "/data", "text/plain"))
        val methodNotAllowed = assertIs<RouteResolution.MethodNotAllowed>(resolution)
        assertTrue(HttpMethod.GET in methodNotAllowed.allowedMethods)
    }

    private fun Router.matchGet(path: String, accept: String? = null): RouteResolution.Matched =
        assertIs(resolveGet(path, accept))

    @Test
    fun `a produces route reports varyOnAccept so the caller can add Vary Accept`() {
        val router = Router()
        router.register(HttpMethod.GET, "/data", produces = listOf("application/json"), handler = {})

        assertTrue(router.matchGet("/data", "application/json").match.varyOnAccept)
    }

    @Test
    fun `a route with no produces does not report varyOnAccept`() {
        val router = Router()
        router.register(HttpMethod.GET, "/plain", handler = {})

        assertFalse(router.matchGet("/plain").match.varyOnAccept)
    }

    @Test
    fun `varyOnAccept holds even when a catch-all on the same path is selected`() {
        val json: RouteHandler = {}
        val anyType: RouteHandler = {}
        val router = Router()
        router.register(HttpMethod.GET, "/data", produces = listOf("application/json"), handler = json)
        router.register(HttpMethod.GET, "/data", handler = anyType) // catch-all (no produces)

        // text/plain falls back to the catch-all, but the resource still
        // varies on Accept (a different Accept would select json).
        val matched = router.matchGet("/data", "text/plain")
        assertSame(anyType, matched.match.handler)
        assertTrue(matched.match.varyOnAccept)
    }

    @Test
    fun `register rejects a malformed produces media type`() {
        val router = Router()
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/data", produces = listOf("notamediatype"), handler = {})
        }
        assertFailsWith<IllegalArgumentException> {
            router.register(HttpMethod.GET, "/x", produces = listOf("application/*"), handler = {})
        }
    }
}
