package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.FlushMode
import kotlin.test.fail

/**
 * Pins that [GzipDecoder] satisfies the [DecoderSession] SPI contract.
 *
 * Encoded payloads are produced by [GzipEncoder] with [FlushMode.NoFlush]
 * to keep the wire format minimal.
 */
class GzipDecoderSessionContractTest : AbstractDecoderSessionContractTest() {

    override fun newSession(): DecoderSession =
        GzipDecoder.newSession(allocator, DecoderOptions())

    override fun newSessionWithOptions(options: DecoderOptions): DecoderSession =
        GzipDecoder.newSession(allocator, options)

    override fun encodeForDecode(payload: ByteArray): ByteArray =
        encodeWithGzip(payload, allocator, outputCap)
}

internal fun encodeWithGzip(
    payload: ByteArray,
    allocator: BufferAllocator,
    outputCap: Int,
): ByteArray = encodeWith(payload, allocator, outputCap) {
    GzipEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush))
}

internal fun encodeWithDeflate(
    payload: ByteArray,
    allocator: BufferAllocator,
    outputCap: Int,
): ByteArray = encodeWith(payload, allocator, outputCap) {
    DeflateEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush))
}

private fun encodeWith(
    payload: ByteArray,
    allocator: BufferAllocator,
    outputCap: Int,
    factory: () -> EncoderSession,
): ByteArray {
    val session = factory()
    val sink = ByteCollector()
    val input = allocator.allocate(maxOf(payload.size, 1))
    val output = allocator.allocate(outputCap)
    try {
        if (payload.isNotEmpty()) {
            input.writeByteArray(payload, 0, payload.size)
        }
        var iters = 0
        while (iters < 4096) {
            when (session.update(input, output)) {
                CodecStatus.NEED_OUTPUT -> sink.drain(output)
                CodecStatus.NEED_INPUT -> {
                    sink.drain(output)
                    break
                }
                CodecStatus.FINISHED -> fail("update must not return FINISHED")
            }
            iters++
        }
        iters = 0
        while (iters < 256) {
            when (session.finish(output)) {
                CodecStatus.NEED_OUTPUT -> sink.drain(output)
                CodecStatus.NEED_INPUT -> sink.drain(output)
                CodecStatus.FINISHED -> {
                    sink.drain(output)
                    break
                }
            }
            iters++
        }
    } finally {
        output.release()
        input.release()
        session.close()
    }
    return sink.toByteArray()
}
