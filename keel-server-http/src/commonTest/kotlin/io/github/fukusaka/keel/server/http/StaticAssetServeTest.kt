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
    }

    @AfterTest
    fun tearDown() {
        transport.close()
        SystemFileSystem.delete(Path(rootDir, "page.html"), mustExist = false)
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
        channel.installHttpServerPipeline(router, emptyList(), ErrorHandlers.DEFAULT, scope)
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
}
