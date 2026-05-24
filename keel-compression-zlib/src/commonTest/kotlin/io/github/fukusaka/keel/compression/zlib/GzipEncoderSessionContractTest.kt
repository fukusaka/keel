package io.github.fukusaka.keel.compression.zlib

import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.FlushMode

/**
 * Pins that [GzipEncoder] satisfies the [EncoderSession] SPI contract.
 *
 * Uses [FlushMode.NoFlush] in the default factory because the contract
 * tests drive small payloads through `update` → `finish` and the default
 * `FlushMode.Sync` would emit additional sync markers that contract
 * tests treat as opaque bytes (still pass) but inflate the output size.
 * Backend-specific framing assertions live in the round-trip tests.
 */
class GzipEncoderSessionContractTest : AbstractEncoderSessionContractTest() {

    override fun newSession(): EncoderSession =
        GzipEncoder.newSession(allocator, EncoderOptions(flushMode = FlushMode.NoFlush))

    override fun newSessionWithOptions(options: EncoderOptions): EncoderSession =
        GzipEncoder.newSession(allocator, options)
}
