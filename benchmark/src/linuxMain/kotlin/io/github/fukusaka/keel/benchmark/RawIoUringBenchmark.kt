package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.iouring.IoModeSelectors
import io.github.fukusaka.keel.engine.iouring.IoUringEngine
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.toKString
import platform.posix.getenv
import io.github.fukusaka.keel.pipeline.typedHandler
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get

/**
 * Zero-suspend io_uring benchmark server using the ChannelPipeline API.
 *
 * Uses [IoUringEngine.bindPipeline] to create a multi-thread server where
 * each worker EventLoop owns a SO_REUSEPORT server socket. The pipeline
 * handler receives data via [onRead] callbacks and sends pre-encoded HTTP
 * responses through the pipeline write path — no coroutine overhead.
 *
 * The response is sent by writing an [IoBuf] containing the pre-encoded
 * HTTP response via [ChannelHandlerContext.propagateWriteAndFlush]. The
 * HeadHandler delegates to [IoUringIoTransport] which does direct `send()`
 * with EAGAIN → SEND SQE fallback.
 *
 * This replaces the earlier `@InternalKeelApi`-based implementation (PR #164)
 * with a public API-only version. No engine internals are accessed.
 */
object RawIoUringBenchmark : EngineBenchmark {

    @OptIn(ExperimentalForeignApi::class)
    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0 // 0 = auto (availableProcessors)
        val modeSelector = when (getenv("BENCH_IO_MODE")?.toKString()) {
            "cqe" -> IoModeSelectors.CQE
            "fallback" -> IoModeSelectors.FALLBACK_CQE
            "sendzc" -> IoModeSelectors.SEND_ZC
            else -> IoModeSelectors.eagainThreshold()
        }
        val engine = IoUringEngine(
            config = IoEngineConfig(
                threads = threads,
                loggerFactory = NoopLoggerFactory,
            ),
            writeModeSelector = modeSelector,
        )

        // Pre-encoded HTTP responses.
        val helloBytes = buildResponse("Hello, World!")
        val largeBytes = buildResponse("x".repeat(LARGE_PAYLOAD_SIZE))

        val server = engine.bindPipeline("0.0.0.0", config.port) { channel ->
            channel.pipeline.addLast("http-handler", typedHandler<IoBuf> { ctx, buf ->
                val response = selectResponse(buf, helloBytes, largeBytes)
                if (response != null) {
                    // Allocate IoBuf, copy pre-encoded response, and write through pipeline.
                    // HeadHandler → IoTransport.write + flush (fire-and-forget direct send).
                    val responseBuf = ctx.allocator.allocate(response.size)
                    responseBuf.writeByteArray(response, 0, response.size)
                    ctx.propagateWrite(responseBuf)
                    ctx.propagateFlush()
                }
            })
        }

        return {
            server.close()
            engine.close()
        }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}

/**
 * Scans received bytes for "GET /hello" or "GET /large" and returns
 * the appropriate pre-encoded response.
 */
@OptIn(ExperimentalForeignApi::class)
private fun selectResponse(
    buf: IoBuf,
    helloResponse: ByteArray,
    largeResponse: ByteArray,
): ByteArray? {
    if (buf.readableBytes < 14) return null
    val ptr = buf.unsafePointer
    val offset = buf.readerIndex

    // Fast path: check "GET /" at offset 0
    if (ptr[offset + 0] != 'G'.code.toByte()) return null
    if (ptr[offset + 1] != 'E'.code.toByte()) return null
    if (ptr[offset + 2] != 'T'.code.toByte()) return null
    if (ptr[offset + 3] != ' '.code.toByte()) return null
    if (ptr[offset + 4] != '/'.code.toByte()) return null

    return when (ptr[offset + 5].toInt().toChar()) {
        'h' -> helloResponse
        'l' -> largeResponse
        else -> null
    }
}

private fun buildResponse(body: String): ByteArray {
    val bytes = body.encodeToByteArray()
    return ("HTTP/1.1 200 OK\r\n" +
        "Content-Type: text/plain\r\n" +
        "Content-Length: ${bytes.size}\r\n" +
        "Connection: keep-alive\r\n" +
        "\r\n").encodeToByteArray() + bytes
}
