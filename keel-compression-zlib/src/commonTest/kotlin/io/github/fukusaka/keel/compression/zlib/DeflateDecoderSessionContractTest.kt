package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession

/** Pins that [DeflateDecoder] satisfies the [DecoderSession] SPI contract. */
class DeflateDecoderSessionContractTest : AbstractDecoderSessionContractTest() {

    override fun newSession(): DecoderSession =
        DeflateDecoder.newSession(allocator, DecoderOptions())

    override fun newSessionWithOptions(options: DecoderOptions): DecoderSession =
        DeflateDecoder.newSession(allocator, options)

    override fun encodeForDecode(payload: ByteArray): ByteArray =
        encodeWithDeflate(payload, allocator, outputCap)
}
