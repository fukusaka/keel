package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * End-to-end serve test for the static-file feature: drives the
 * keel-server-http pipeline (built via the `staticFiles` DSL) directly
 * over a [TestIoTransport], with the assets backed by a real temp
 * directory.
 *
 * The transport's `ioDispatcher` is [Dispatchers.Unconfined], so the
 * request coroutine runs inline within `notifyRead` and the round-trip
 * completes synchronously — no wall-clock wait to bound.
 */
class StaticAssetServeTest {

    private lateinit var rootDir: Path
    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeTest
    fun setUp() {
        rootDir = Path(SystemTemporaryDirectory, "keel-serve-${Random.nextLong()}")
        SystemFileSystem.createDirectories(rootDir)
        SystemFileSystem.sink(Path(rootDir, "page.html")).buffered().use { it.writeString("<h1>hi</h1>") }
        // A 10-byte file with distinct bytes makes range offsets verifiable.
        SystemFileSystem.sink(Path(rootDir, "data.txt")).buffered().use { it.writeString(RANGE_DATA) }
    }

    @AfterTest
    fun tearDown() {
        transport.close()
        SystemFileSystem.delete(Path(rootDir, "page.html"), mustExist = false)
        SystemFileSystem.delete(Path(rootDir, "data.txt"), mustExist = false)
        SystemFileSystem.delete(rootDir, mustExist = false)
    }

    private fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    private fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    private fun responseText(): String = transport.written.joinToString("") { it.readString() }

    /**
     * Installs a pipeline whose router serves [rootDir] at `/assets`,
     * registered exactly as the `staticFiles` DSL does (a `GET` + `HEAD`
     * wildcard route delegating to a [StaticAssetHandler]).
     */
    private fun installStaticFiles() {
        val handler = StaticAssetHandler(FilesystemAssetSource(root = rootDir.toString()))
        val router = Router()
        router.register(HttpMethod.GET, "/assets/*") { call -> handler.handle(call) }
        router.register(HttpMethod.HEAD, "/assets/*") { call -> handler.handle(call) }
        channel.installHttpServerPipeline(
            router,
            emptyList(),
            ErrorHandlers.DEFAULT,
            QueryParameterConfig.DEFAULT,
            scope,
        )
    }

    private fun feed(method: String, path: String, extraHeaders: String = "") {
        channel.pipeline.notifyRead(
            bufOf("$method $path HTTP/1.1\r\nHost: localhost\r\n$extraHeaders\r\n"),
        )
    }

    @Test
    fun `a GET for a served file returns 200 with its body and content type`() {
        installStaticFiles()

        feed("GET", "/assets/page.html")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "status line: $text")
        assertTrue(text.contains("text/html", ignoreCase = true), "content-type: $text")
        assertTrue(text.contains("<h1>hi</h1>"), "body: $text")
    }

    @Test
    fun `a HEAD for a served file returns headers without the body`() {
        installStaticFiles()

        feed("HEAD", "/assets/page.html")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "status line: $text")
        assertTrue(!text.contains("<h1>hi</h1>"), "HEAD must not carry a body: $text")
    }

    @Test
    fun `a GET for a missing file returns 404`() {
        installStaticFiles()

        feed("GET", "/assets/absent.html")

        assertTrue(responseText().startsWith("HTTP/1.1 404"), "expected 404: ${responseText()}")
    }

    @Test
    fun `a percent-encoded traversal escape returns 404`() {
        installStaticFiles()

        feed("GET", "/assets/%2e%2e/page.html")

        assertTrue(responseText().startsWith("HTTP/1.1 404"), "expected 404: ${responseText()}")
    }

    @Test
    fun `a conditional GET matching the ETag returns 304 with no body`() {
        installStaticFiles()
        // First request: capture the ETag.
        feed("GET", "/assets/page.html")
        val etag = etagOf(responseText())
        transport.written.forEach { it.release() }
        transport.written.clear()

        feed("GET", "/assets/page.html", "If-None-Match: $etag\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 304"), "expected 304: $text")
        assertTrue(!text.contains("<h1>hi</h1>"), "304 must not carry a body: $text")
    }

    private fun etagOf(response: String): String {
        val line = response.lineSequence().first { it.startsWith("ETag:", ignoreCase = true) }
        return line.substringAfter(':').trim()
    }

    /** The response body — everything after the blank line ending the headers. */
    private fun bodyOf(response: String): String = response.substringAfter("\r\n\r\n", "")

    /** The value of [name] in the response header block, or null when absent. */
    private fun headerOf(response: String, name: String): String? {
        val headerBlock = response.substringBefore("\r\n\r\n")
        val line = headerBlock.lineSequence().firstOrNull { it.startsWith("$name:", ignoreCase = true) }
        return line?.substringAfter(':')?.trim()
    }

    @Test
    fun `a Range request returns 206 with the requested bytes`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt", "Range: bytes=0-3\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 206"), "expected 206: $text")
        assertEquals("bytes 0-3/10", headerOf(text, "Content-Range"))
        assertEquals(RANGE_DATA.substring(0, 4), bodyOf(text))
    }

    @Test
    fun `an open-ended Range returns 206 from the offset to the end`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt", "Range: bytes=2-\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 206"), "expected 206: $text")
        assertEquals("bytes 2-9/10", headerOf(text, "Content-Range"))
        assertEquals(RANGE_DATA.substring(2), bodyOf(text))
    }

    @Test
    fun `a suffix Range returns 206 with the last bytes`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt", "Range: bytes=-3\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 206"), "expected 206: $text")
        assertEquals("bytes 7-9/10", headerOf(text, "Content-Range"))
        assertEquals(RANGE_DATA.substring(7), bodyOf(text))
    }

    @Test
    fun `a single-byte Range for the last byte returns 206`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt", "Range: bytes=9-9\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 206"), "expected 206: $text")
        assertEquals("bytes 9-9/10", headerOf(text, "Content-Range"))
        assertEquals(RANGE_DATA.substring(9), bodyOf(text))
    }

    @Test
    fun `a Range whose end is past the file is clamped`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt", "Range: bytes=5-999\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 206"), "expected 206: $text")
        assertEquals("bytes 5-9/10", headerOf(text, "Content-Range"))
        assertEquals(RANGE_DATA.substring(5), bodyOf(text))
    }

    @Test
    fun `a Range starting past the file returns 416`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt", "Range: bytes=20-30\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 416"), "expected 416: $text")
        assertEquals("bytes */10", headerOf(text, "Content-Range"))
        assertEquals("", bodyOf(text), "416 must not carry a body: $text")
    }

    @Test
    fun `a malformed Range is ignored and the full file is served`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt", "Range: bytes=5-2\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "expected 200: $text")
        assertEquals(RANGE_DATA, bodyOf(text))
    }

    @Test
    fun `a multi-range request returns a 206 multipart byteranges body`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt", "Range: bytes=0-1,5-6\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 206"), "expected 206: $text")
        val contentType = headerOf(text, "Content-Type") ?: ""
        assertTrue(contentType.startsWith("multipart/byteranges; boundary="), "content-type: $text")
        val boundary = contentType.substringAfter("boundary=")

        // RFC 9110 §14.6: byte-exact body for the two parts plus the closing delimiter.
        val expected =
            "--$boundary\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Range: bytes 0-1/10\r\n\r\n" +
                RANGE_DATA.substring(0, 2) + "\r\n" +
                "--$boundary\r\n" +
                "Content-Type: text/plain; charset=utf-8\r\n" +
                "Content-Range: bytes 5-6/10\r\n\r\n" +
                RANGE_DATA.substring(5, 7) + "\r\n" +
                "--$boundary--\r\n"
        assertEquals(expected, bodyOf(text), "multipart body must be byte-exact: $text")
        assertEquals(
            expected.encodeToByteArray().size.toString(),
            headerOf(text, "Content-Length"),
            "Content-Length must equal the multipart body length",
        )
        assertEquals(null, headerOf(text, "Content-Range"), "no top-level Content-Range on a multipart 206")
    }

    @Test
    fun `out-of-order multi-range parts are sorted ascending in the multipart body`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt", "Range: bytes=6-7,0-1\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 206"), "expected 206: $text")
        val body = bodyOf(text)
        assertTrue(
            body.indexOf("bytes 0-1/10") < body.indexOf("bytes 6-7/10"),
            "coalesced ranges must be ascending: $body",
        )
    }

    @Test
    fun `coalescing overlapping ranges yields a single-range 206`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt", "Range: bytes=0-3,2-5\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 206"), "expected 206: $text")
        assertEquals("bytes 0-5/10", headerOf(text, "Content-Range"))
        assertEquals(RANGE_DATA.substring(0, 6), bodyOf(text))
    }

    @Test
    fun `a HEAD with a multi-range returns multipart headers without a body`() {
        installStaticFiles()

        feed("HEAD", "/assets/data.txt", "Range: bytes=0-1,5-6\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 206"), "expected 206: $text")
        val contentType = headerOf(text, "Content-Type") ?: ""
        assertTrue(contentType.startsWith("multipart/byteranges; boundary="), "content-type: $text")
        assertTrue(headerOf(text, "Content-Length") != null, "Content-Length present: $text")
        assertEquals("", bodyOf(text), "HEAD must not carry a body: $text")
    }

    @Test
    fun `a multi-range with all parts unsatisfiable returns 416`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt", "Range: bytes=20-30,40-50\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 416"), "expected 416: $text")
        assertEquals("bytes */10", headerOf(text, "Content-Range"))
        assertEquals("", bodyOf(text), "416 must not carry a body: $text")
    }

    @Test
    fun `a partly-satisfiable multi-range serves only the satisfiable ranges`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt", "Range: bytes=0-2,50-60\r\n")

        val text = responseText()
        // 50-60 is dropped; only 0-2 remains, so a single-range 206.
        assertTrue(text.startsWith("HTTP/1.1 206"), "expected 206: $text")
        assertEquals("bytes 0-2/10", headerOf(text, "Content-Range"))
        assertEquals(RANGE_DATA.substring(0, 3), bodyOf(text))
    }

    @Test
    fun `an If-Range with a non-matching entity tag serves the full 200`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt", "If-Range: \"nomatch\"\r\nRange: bytes=0-3\r\n")

        val text = responseText()
        // keel ETags are weak, so an entity-tag If-Range never strong-matches.
        assertTrue(text.startsWith("HTTP/1.1 200"), "expected 200: $text")
        assertEquals(RANGE_DATA, bodyOf(text))
    }

    @Test
    fun `an If-Range with the asset's own weak ETag still serves the full 200`() {
        installStaticFiles()
        feed("GET", "/assets/data.txt")
        val etag = etagOf(responseText())
        transport.written.forEach { it.release() }
        transport.written.clear()

        feed("GET", "/assets/data.txt", "If-Range: $etag\r\nRange: bytes=0-3\r\n")

        val text = responseText()
        // A weak tag fails the strong comparison required by RFC 9110 §13.1.5.
        assertTrue(text.startsWith("HTTP/1.1 200"), "expected 200: $text")
        assertEquals(RANGE_DATA, bodyOf(text))
    }

    @Test
    fun `an If-Range with the matching Last-Modified date honours the Range`() {
        installStaticFiles()
        feed("GET", "/assets/data.txt")
        val lastModified = headerOf(responseText(), "Last-Modified")
        assertTrue(lastModified != null, "asset must expose Last-Modified for this test")
        transport.written.forEach { it.release() }
        transport.written.clear()

        feed("GET", "/assets/data.txt", "If-Range: $lastModified\r\nRange: bytes=0-3\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 206"), "expected 206: $text")
        assertEquals("bytes 0-3/10", headerOf(text, "Content-Range"))
    }

    @Test
    fun `an If-Range with a non-matching date serves the full 200`() {
        installStaticFiles()

        feed(
            "GET",
            "/assets/data.txt",
            "If-Range: Sun, 06 Nov 1994 08:49:37 GMT\r\nRange: bytes=0-3\r\n",
        )

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 200"), "expected 200: $text")
        assertEquals(RANGE_DATA, bodyOf(text))
    }

    @Test
    fun `a multi-range request that also matches the ETag returns 304`() {
        installStaticFiles()
        feed("GET", "/assets/data.txt")
        val etag = etagOf(responseText())
        transport.written.forEach { it.release() }
        transport.written.clear()

        feed("GET", "/assets/data.txt", "If-None-Match: $etag\r\nRange: bytes=0-1,5-6\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 304"), "conditional GET wins over a multi-range: $text")
        assertEquals("", bodyOf(text), "304 must not carry a body: $text")
    }

    @Test
    fun `a normal 200 response advertises Accept-Ranges`() {
        installStaticFiles()

        feed("GET", "/assets/data.txt")

        assertEquals("bytes", headerOf(responseText(), "Accept-Ranges"))
    }

    @Test
    fun `a HEAD with a Range returns 206 headers without a body`() {
        installStaticFiles()

        feed("HEAD", "/assets/data.txt", "Range: bytes=0-3\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 206"), "expected 206: $text")
        assertEquals("bytes 0-3/10", headerOf(text, "Content-Range"))
        assertEquals("", bodyOf(text), "HEAD must not carry a body: $text")
    }

    @Test
    fun `a Range request that also matches the ETag returns 304`() {
        installStaticFiles()
        feed("GET", "/assets/data.txt")
        val etag = etagOf(responseText())
        transport.written.forEach { it.release() }
        transport.written.clear()

        feed("GET", "/assets/data.txt", "If-None-Match: $etag\r\nRange: bytes=0-3\r\n")

        val text = responseText()
        assertTrue(text.startsWith("HTTP/1.1 304"), "conditional GET wins over Range: $text")
        assertEquals("", bodyOf(text), "304 must not carry a body: $text")
    }

    private companion object {

        /** A 10-byte fixture with distinct bytes so range offsets are verifiable. */
        const val RANGE_DATA = "0123456789"
    }
}
