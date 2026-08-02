package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.codec.http.HttpBody
import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import java.io.InputStream
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test: `pipeline-http-netty` POST `/upload-stream` must report
 * the correct number of received bytes in `X-Bytes-Received`.
 *
 * Red-Green TDD: this test was written before the fix. It fails on the
 * buggy implementation (`is HttpBody` branch matching `HttpBodyEnd` due to
 * subtype ordering in `when`) and passes after the fix (is HttpBodyEnd before
 * is HttpBody).
 */
class NettyPipelineUploadTest {

    /**
     * Upload-counting handler. Mirrors the relevant part of
     * `BenchmarkRoutingHandler` without pulling in the full benchmark module.
     *
     * Protocol: HttpRequestHead → HttpBody* → HttpBodyEnd.
     * On HttpBodyEnd, emits "HTTP/1.1 200 OK\r\nX-Bytes-Received: N\r\n..." response.
     *
     * IMPORTANT: `is HttpBodyEnd` must appear before `is HttpBody` in the `when`
     * block because `HttpBodyEnd` is a subtype of `HttpBody` — Kotlin checks
     * branches in order, so `is HttpBody` would shadow `is HttpBodyEnd` if listed
     * first.
     */
    private class UploadCountHandler : InboundHandler {
        private var uploadStreaming = false
        private var uploadBytes = 0L

        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            when (msg) {
                is HttpRequestHead -> {
                    uploadStreaming = msg.path == "/upload-stream"
                    uploadBytes = 0L
                }
                is HttpBodyEnd -> {
                    if (uploadStreaming) {
                        uploadBytes += msg.content.readableBytes
                        ctx.propagateWrite(
                            HttpResponse.ok("ok", contentType = "text/plain").apply {
                                headers.add("X-Bytes-Received", uploadBytes.toString())
                            },
                        )
                        ctx.propagateFlush()
                        uploadStreaming = false
                    }
                    msg.content.release()
                }
                is HttpBody -> {
                    if (uploadStreaming) uploadBytes += msg.content.readableBytes
                    msg.content.release()
                }
                else -> ctx.propagateRead(msg)
            }
        }
    }

    /**
     * Reads all HTTP response headers from [input], stopping at the blank line.
     * Returns the header block as a single string (includes the status line).
     */
    private fun readHttpResponseHeaders(input: InputStream): String {
        val sb = StringBuilder()
        var prev = -1
        var prevPrev = -1
        var prevPrevPrev = -1
        while (true) {
            val b = input.read()
            if (b == -1) break
            sb.append(b.toChar())
            // Detect \r\n\r\n (end of headers)
            if (prevPrevPrev == '\r'.code && prevPrev == '\n'.code &&
                prev == '\r'.code && b == '\n'.code
            ) {
                break
            }
            prevPrevPrev = prevPrev
            prevPrev = prev
            prev = b
        }
        return sb.toString()
    }

    @Test
    fun `pipeline-http-netty POST upload-stream reports correct byte count for small body`() = runTest {
        val engine = NettyEngine()
        val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
            channel.pipeline.addLast("encoder", HttpResponseEncoder())
            channel.pipeline.addLast("decoder", HttpRequestDecoder())
            channel.pipeline.addLast("upload", UploadCountHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        val body = "hello world from netty"
        val request =
            "POST /upload-stream HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "\r\n" +
                body

        val client = connectRawClient(port)
        try {
            rawWrite(client, request)
            val headers = readHttpResponseHeaders(client.getInputStream())
            val xBytesReceived = headers.lines()
                .firstOrNull { it.startsWith("X-Bytes-Received:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()?.toLongOrNull()
            assertEquals(
                body.length.toLong(),
                xBytesReceived,
                "expected X-Bytes-Received=${body.length}, got headers:\n$headers",
            )
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }

    @Test
    fun `pipeline-http-netty POST upload-stream reports correct byte count for 64KB body`() = runTest {
        val engine = NettyEngine()
        val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
            channel.pipeline.addLast("encoder", HttpResponseEncoder())
            channel.pipeline.addLast("decoder", HttpRequestDecoder())
            channel.pipeline.addLast("upload", UploadCountHandler())
        }
        val port = (server.localAddress as InetSocketAddress).port

        val bodySize = 65536
        val body = "x".repeat(bodySize)
        val request =
            "POST /upload-stream HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: $bodySize\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "\r\n" +
                body

        val client = connectRawClient(port)
        try {
            rawWrite(client, request)
            val headers = readHttpResponseHeaders(client.getInputStream())
            val xBytesReceived = headers.lines()
                .firstOrNull { it.startsWith("X-Bytes-Received:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()?.toLongOrNull()
            assertEquals(
                bodySize.toLong(),
                xBytesReceived,
                "expected X-Bytes-Received=$bodySize, got headers:\n$headers",
            )
        } finally {
            client.close()
            server.close()
            engine.close()
        }
    }
}
