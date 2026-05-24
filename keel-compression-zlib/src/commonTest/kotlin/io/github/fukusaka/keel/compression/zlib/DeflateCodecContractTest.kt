package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.compression.CompressionCodec

/** Pins that [DeflateCodec] satisfies the [CompressionCodec] SPI contract. */
class DeflateCodecContractTest : AbstractCompressionCodecContractTest() {
    override val codec: CompressionCodec = DeflateCodec
}
