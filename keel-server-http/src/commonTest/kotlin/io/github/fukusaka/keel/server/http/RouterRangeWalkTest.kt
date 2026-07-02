package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the segment semantics of [Router.resolve]'s range walk to the
 * `split('/') + filter(isNotEmpty)` reference it replaced: empty segments
 * (leading / doubled / trailing slashes) are skipped, wildcard captures
 * join the *normalised* remaining segments, and literal matching by
 * (hash, `regionMatches`) disambiguates true `String.hashCode` collisions.
 * Pure synchronous logic — no I/O, no timeout needed.
 */
class RouterRangeWalkTest {

    private val handler: RouteHandler = { }

    private fun headFor(method: HttpMethod, path: String): HttpRequestHead =
        HttpRequestHead(method = method, uri = path, headers = HttpHeaders())

    private fun Router.match(method: HttpMethod, path: String): RouteMatch? {
        val resolution = resolve(method, path, headFor(method, path))
        return (resolution as? RouteResolution.Matched)?.match
    }

    @Test
    fun `doubled and trailing slashes resolve like the normalised path`() {
        val router = Router()
        router.register(HttpMethod.GET, "/items/:id", handler = handler)
        for (path in listOf("/items/5", "//items/5", "/items//5", "/items/5/", "//items///5//")) {
            val match = router.match(HttpMethod.GET, path)
            assertSame(handler, match?.handler, "path '$path' did not resolve")
            assertEquals(mapOf("id" to "5"), match?.pathParameters, "path '$path' captured wrong params")
        }
    }

    @Test
    fun `root route resolves for slash and empty path`() {
        val router = Router()
        router.register(HttpMethod.GET, "/", handler = handler)
        assertSame(handler, router.match(HttpMethod.GET, "/")?.handler)
        assertSame(handler, router.match(HttpMethod.GET, "")?.handler)
        assertSame(handler, router.match(HttpMethod.GET, "///")?.handler)
    }

    @Test
    fun `wildcard capture joins normalised remaining segments`() {
        val router = Router()
        router.register(HttpMethod.GET, "/files/*", handler = handler)
        // Doubled / trailing slashes normalise away in the capture, exactly
        // like the former segments.subList(...).joinToString("/").
        assertEquals(mapOf("*" to "a/b"), router.match(HttpMethod.GET, "/files//a/b/")?.pathParameters)
        assertEquals(mapOf("*" to "a/b/c"), router.match(HttpMethod.GET, "/files/a/b/c")?.pathParameters)
    }

    @Test
    fun `wildcard route answers its bare prefix with an empty capture`() {
        val router = Router()
        router.register(HttpMethod.GET, "/files/*", handler = handler)
        assertEquals(mapOf("*" to ""), router.match(HttpMethod.GET, "/files")?.pathParameters)
        assertEquals(mapOf("*" to ""), router.match(HttpMethod.GET, "/files/")?.pathParameters)
    }

    @Test
    fun `literal segments with colliding hashCodes resolve to their own routes`() {
        // "Aa" and "BB" have identical String.hashCode (2112) — the range
        // lookup's hash pre-filter must fall through to regionMatches.
        assertEquals("Aa".hashCode(), "BB".hashCode(), "test precondition: collision pair")
        val aa: RouteHandler = { }
        val bb: RouteHandler = { }
        val router = Router()
        router.register(HttpMethod.GET, "/Aa", handler = aa)
        router.register(HttpMethod.GET, "/BB", handler = bb)
        assertSame(aa, router.match(HttpMethod.GET, "/Aa")?.handler)
        assertSame(bb, router.match(HttpMethod.GET, "/BB")?.handler)
    }

    @Test
    fun `segment that shares a prefix with a literal key does not match it`() {
        val router = Router()
        router.register(HttpMethod.GET, "/items", handler = handler)
        assertNull(router.match(HttpMethod.GET, "/item"))
        assertNull(router.match(HttpMethod.GET, "/itemsz"))
    }

    @Test
    fun `params capture the raw segment even with surrounding empty segments`() {
        val router = Router()
        router.register(HttpMethod.GET, "/a/:x/b", handler = handler)
        assertEquals(mapOf("x" to "mid"), router.match(HttpMethod.GET, "//a//mid//b//")?.pathParameters)
    }

    @Test
    fun `unmatched deep path with doubled slashes still reports MethodNotAllowed correctly`() {
        // The 405 path re-derives the segment list lazily (cold path) — it
        // must agree with the walk about which node the path names.
        val router = Router()
        router.register(HttpMethod.POST, "/a/b", handler = handler)
        val resolution = router.resolve(HttpMethod.GET, "//a//b/", headFor(HttpMethod.GET, "//a//b/"))
        assertTrue(resolution is RouteResolution.MethodNotAllowed)
        assertEquals(setOf(HttpMethod.POST), resolution.allowedMethods)
    }
}
