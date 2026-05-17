package io.github.fukusaka.keel.server.http

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Unit tests for [ContentTypeResolver.Default]. */
class ContentTypeResolverTest {

    private val resolver = ContentTypeResolver.Default

    @Test
    fun `a known text extension carries an explicit charset`() {
        assertEquals("text/html; charset=utf-8", resolver.resolve("index.html"))
        assertEquals("text/css; charset=utf-8", resolver.resolve("site.css"))
        assertEquals("text/javascript; charset=utf-8", resolver.resolve("app.mjs"))
    }

    @Test
    fun `a known binary extension resolves to its media type`() {
        assertEquals("image/png", resolver.resolve("logo.png"))
        assertEquals("image/jpeg", resolver.resolve("photo.jpeg"))
        assertEquals("application/wasm", resolver.resolve("module.wasm"))
        assertEquals("font/woff2", resolver.resolve("font.woff2"))
    }

    @Test
    fun `extension matching is case-insensitive`() {
        assertEquals("image/png", resolver.resolve("LOGO.PNG"))
    }

    @Test
    fun `an unknown extension resolves to null`() {
        assertNull(resolver.resolve("archive.xyz"))
    }

    @Test
    fun `a name with no extension resolves to null`() {
        assertNull(resolver.resolve("README"))
    }

    @Test
    fun `a trailing dot resolves to null`() {
        assertNull(resolver.resolve("file."))
    }
}
