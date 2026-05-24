package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.FlushMode

/**
 * Pins that [DeflateEncoder] satisfies the [EncoderSession] SPI
 * contract. See [GzipEncoderSessionContractTest] for the `NoFlush`
 * rationale.
 */
class DeflateEncoderSessionContractTest : AbstractEncoderSessionContractTest() {

    override fun newSession(): EncoderSession =
        DeflateEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush))

    override fun newSessionWithOptions(options: EncoderOptions): EncoderSession =
        DeflateEncoder.newSession(allocator, options)
}
