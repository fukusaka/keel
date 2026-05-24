package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.compression.CompressionCodec

/** Pins that [GzipCodec] satisfies the [CompressionCodec] SPI contract. */
class GzipCodecContractTest : AbstractCompressionCodecContractTest() {
    override val codec: CompressionCodec = GzipCodec
}
