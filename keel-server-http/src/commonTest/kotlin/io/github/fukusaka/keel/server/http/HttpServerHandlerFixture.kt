package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.AfterTest

/**
 * The fixture shared by the pipeline-level integration tests for the
 * keel-server-http server stack ([installHttpServerPipeline]): raw HTTP/1.1
 * request bytes in, encoded response bytes out, via a [Router].
 *
 * Drives the pipeline directly over a [TestIoTransport] so no real engine or
 * socket is needed. The transport's `ioDispatcher` is [Dispatchers.Unconfined],
 * so the request coroutine launched by [HttpServerHandler] runs inline on the
 * test thread — the request / response round-trip completes synchronously
 * within `notifyRead`, **with no wall-clock wait to bound**. That is why none
 * of the `HttpServer*Test` classes wraps its tests in a timeout.
 *
 * `KeelHttpServer.start()` / `stop()` (the `bindPipeline` wiring) are exercised
 * by per-engine tests; these cover the request-handling pipeline that `start()`
 * installs.
 *
 * Holds the transport and channel they run against, the `feed*` helpers that
 * push a request through, and the recording upgrade protocol. Nested and
 * `protected` rather than hoisted to package scope — `bufOf` and `install` are
 * names sibling test files in this package declare for themselves.
 */
internal abstract class HttpServerHandlerFixture {

    /**
     * Snapshots the encoded response bytes the instant the channel closes,
     * before [TestIoTransport.close] releases and clears [TestIoTransport.written].
     * Drain tests close the channel as part of the request lifecycle, so
     * the response would otherwise be unobservable afterwards.
     */
    protected var responseAtClose: String? = null

    /**
     * Named rather than anonymous: the tests read [writableOverride] and
     * [awaitPendingFlushCount], and a non-private declaration of an anonymous
     * object exposes only its supertype, which does not have them. `inner`
     * because its `close()` records into the fixture's own `responseAtClose`.
     */
    protected inner class RecordingTransport : TestIoTransport() {
        /**
         * When non-null, replaces the [isWritable] reading the sealed
         * `writable` field on [TestIoTransport] / [AbstractIoTransport].
         * Lets a backpressure test pin the gate open without simulating
         * a full pendingBytes overflow.
         */
        var writableOverride: Boolean? = null

        /** Counts every [awaitPendingFlush] call so a test can assert the
         *  backpressure gate fired (and how many times). */
        var awaitPendingFlushCount: Int = 0
            private set

        override val isWritable: Boolean
            get() = writableOverride ?: super.isWritable

        override suspend fun awaitPendingFlush() {
            awaitPendingFlushCount++
            super.awaitPendingFlush()
        }

        override fun close() {
            if (!closed) {
                responseAtClose = written.joinToString("") { buf ->
                    val bytes = ByteArray(buf.readableBytes)
                    buf.readByteArray(bytes, 0, bytes.size)
                    bytes.decodeToString()
                }
            }
            super.close()
        }
    }

    protected val transport = RecordingTransport()
    protected val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {}
    protected val scope = CoroutineScope(Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        transport.close()
    }

    protected fun IoBuf.readString(): String {
        val bytes = ByteArray(readableBytes)
        readByteArray(bytes, 0, bytes.size)
        return bytes.decodeToString()
    }

    protected fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    protected fun responseText(): String =
        transport.written.joinToString("") { it.readString() }

    protected fun install(
        router: Router,
        middlewares: List<Middleware> = emptyList(),
        errorHandlers: ErrorHandlers = ErrorHandlers.DEFAULT,
        queryParameterConfig: QueryParameterConfig = QueryParameterConfig.DEFAULT,
    ) {
        channel.installHttpServerPipeline(router, middlewares, errorHandlers, queryParameterConfig, scope)
    }

    protected fun feedGet(path: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "GET $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n",
            ),
        )
    }

    /** Feeds a request with [method] and an extra `X-Format: <value>` header. */
    protected fun feedWithFormat(method: String, path: String, format: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "$method $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "X-Format: $format\r\n" +
                    "\r\n",
            ),
        )
    }

    /** Feeds a `GET` carrying an `Accept: <value>` header (for content-negotiation tests). */
    protected fun feedWithAccept(path: String, accept: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "GET $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Accept: $accept\r\n" +
                    "\r\n",
            ),
        )
    }

    /** Feeds a `GET` carrying two separate `Accept` header lines (list-based field split across lines). */
    protected fun feedWithTwoAccepts(path: String, accept1: String, accept2: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "GET $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Accept: $accept1\r\n" +
                    "Accept: $accept2\r\n" +
                    "\r\n",
            ),
        )
    }

    /** Feeds a bodyless request with an arbitrary [method] (for method-mismatch tests). */
    protected fun feedMethod(method: String, path: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "$method $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "\r\n",
            ),
        )
    }

    /** Feeds a `GET` carrying an `Upgrade: <token>` header. */
    protected fun feedUpgrade(path: String, upgradeToken: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "GET $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: $upgradeToken\r\n" +
                    "\r\n",
            ),
        )
    }

    protected fun feedPost(path: String, body: String) {
        channel.pipeline.notifyRead(
            bufOf(
                "POST $path HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Content-Length: ${body.encodeToByteArray().size}\r\n" +
                    "\r\n" +
                    body,
            ),
        )
    }

    /** Feeds a chunked-transfer-encoding POST so the decoder emits one `HttpBody` per chunk. */
    protected fun feedPostChunked(path: String, vararg chunks: String) {
        val sb = StringBuilder(
            "POST $path HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n",
        )
        for (chunk in chunks) {
            val size = chunk.encodeToByteArray().size
            sb.append(size.toString(16)).append("\r\n").append(chunk).append("\r\n")
        }
        sb.append("0\r\n\r\n")
        channel.pipeline.notifyRead(bufOf(sb.toString()))
    }

    /** The installed dispatch handler — the connection drained by [KeelHttpServer.stop]. */
    protected fun handler(): HttpServerHandler =
        channel.pipeline.get(HTTP_SERVER_HANDLER_NAME) as HttpServerHandler

    /** All `Vary` field-name tokens across every `Vary` line of [responseText]. */
    protected fun varyTokensOf(responseText: String): List<String> =
        responseText.lineSequence()
            .filter { it.startsWith("Vary:", ignoreCase = true) }
            .flatMap { it.substringAfter(':').split(',').asSequence() }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

    /** An [UpgradeProtocol] test double that records its dispatch and replies. */
    protected class RecordingUpgrade(override val name: String) : UpgradeProtocol {
        var invoked: Boolean = false
        var seenParams: Map<String, String>? = null

        override suspend fun upgrade(call: HttpCall, channel: PipelinedChannel) {
            invoked = true
            seenParams = call.pathParameters
            call.respond(HttpResponse.ok("upgraded"))
        }
    }
}
