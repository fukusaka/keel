package io.github.fukusaka.keel.codec.http

import com.sun.management.ThreadMXBean
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * Per-cycle allocation of the **request parse path**: a raw HTTP request
 * buffer driven through [HttpRequestDecoder] into an [HttpHeaders],
 * mirroring what the server does per request before the handler runs.
 *
 * This is the comparison vehicle for the L7-a-ii experiment (design.md
 * §50): the dominant parse-time cost is materializing each header
 * name/value into a `String` (`bufRangeToString` = `decodeToString` +
 * `copyOfRange`), surfaced by the real-load JFR profile as the largest
 * keel-side allocation lever. The same benchmark runs unchanged on:
 *
 * - **baseline** (C2-v5 String storage): parse materializes N×2 Strings.
 * - **Variant A** (CharSequence-first API, C2-v5 storage): same parse
 *   materialization — the API widening to `CharSequence` is expected to
 *   be alloc-neutral since the stored value already is a `String`.
 * - **Variant B** (byte-range view storage): parse stores IoBuf ranges,
 *   no per-header `String` — expected to drop the materialization.
 *
 * Two scenarios: A — CDN-realistic N=23 request, parse + release only
 * (isolates parse-time alloc). B — same, plus reading 3 headers (adds
 * the access-side cost, where Variant B pays a per-access view).
 */
class HttpRequestParseAllocBenchmark {

    private val tmx = ManagementFactory.getThreadMXBean() as ThreadMXBean

    private val transport = TestIoTransport()
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("parse-bench")) {}

    private class HeadCollector : InboundHandler {
        var lastHead: HttpRequestHead? = null
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg is HttpRequestHead) lastHead = msg
            // Drop body messages (release if they carry buffers).
            if (msg is HttpBody) msg.content.release()
        }
        override fun onError(ctx: PipelineHandlerContext, cause: Throwable) { /* ignore */ }
    }

    private val collector = HeadCollector()

    init {
        channel.pipeline.addLast("decoder", HttpRequestDecoder())
        channel.pipeline.addLast("collector", collector)
    }

    private val request: ByteArray = buildString {
        append("GET /hello HTTP/1.1\r\n")
        append("Host: api.example.com\r\n")
        append("User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X) AppleWebKit/605.1.15\r\n")
        append("Accept: text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8\r\n")
        append("Accept-Language: en-US,en;q=0.9\r\n")
        append("Accept-Encoding: gzip, deflate, br\r\n")
        append("Connection: keep-alive\r\n")
        append("Cookie: session=abc123; tracking=xyz789; consent=accepted; ab_variant=B\r\n")
        append("Upgrade-Insecure-Requests: 1\r\n")
        append("Sec-Fetch-Dest: document\r\n")
        append("Sec-Fetch-Mode: navigate\r\n")
        append("CF-Connecting-IP: 203.0.113.42\r\n")
        append("CF-IPCountry: US\r\n")
        append("CF-Ray: abc123def456-DFW\r\n")
        append("CF-Visitor: {\"scheme\":\"https\"}\r\n")
        append("X-Forwarded-For: 203.0.113.42, 172.16.0.1\r\n")
        append("X-Forwarded-Proto: https\r\n")
        append("X-Real-IP: 203.0.113.42\r\n")
        append("traceparent: 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01\r\n")
        append("tracestate: rojo=00f067aa0ba902b7,congo=t61rcWkgMzE\r\n")
        append("X-Request-ID: 550e8400-e29b-41d4-a716-446655440000\r\n")
        append("Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig\r\n")
        append("CDN-Loop: cloudflare; subreqs=1\r\n")
        append("\r\n")
    }.encodeToByteArray()

    private fun feed(): IoBuf {
        val buf = DefaultAllocator.allocate(request.size)
        buf.writeByteArray(request, 0, request.size)
        return buf
    }

    private fun parseOnly() {
        channel.pipeline.notifyRead(feed())
        collector.lastHead?.headers?.release()
        collector.lastHead = null
    }

    private fun parseAndAccess() {
        channel.pipeline.notifyRead(feed())
        val h = collector.lastHead?.headers
        if (h != null) {
            // mimic a handler reading a few headers
            sink += (h["Host"]?.length ?: 0)
            sink += (h["Content-Type"]?.length ?: 0)
            sink += (h["Accept-Encoding"]?.length ?: 0)
            h.release()
        }
        collector.lastHead = null
    }

    @Suppress("unused")
    private var sink = 0

    private fun measure(iters: Int, body: () -> Unit): Long {
        repeat(WARMUP) { body() }
        val tid = Thread.currentThread().threadId()
        val start = tmx.getThreadAllocatedBytes(tid)
        repeat(iters) { body() }
        val end = tmx.getThreadAllocatedBytes(tid)
        return (end - start) / iters
    }

    private fun median(trials: Int, m: () -> Long): Long =
        LongArray(trials) { m() }.also { it.sort() }[trials / 2]

    @Test
    fun `request parse allocation per cycle`() {
        val parse = median(TRIALS) { measure(ITERS, ::parseOnly) }
        val parseAccess = median(TRIALS) { measure(ITERS, ::parseAndAccess) }
        println("=== HttpRequest parse alloc (CDN N=23, bytes/cycle, iters=$ITERS × $TRIALS) ===")
        println("  A — parse + release only:        $parse bytes/cycle")
        println("  B — parse + access 3 headers:    $parseAccess bytes/cycle")
    }

    companion object {
        private const val WARMUP = 3_000
        private const val ITERS = 20_000
        private const val TRIALS = 5
    }
}
