package io.github.fukusaka.keel.tls

/**
 * Result of a [TlsCodec.protect] or [TlsCodec.unprotect] operation.
 *
 * @param status The outcome of the operation.
 * @param bytesConsumed Number of bytes consumed from the input buffer.
 * @param bytesProduced Number of bytes written to the output buffer.
 */
data class TlsCodecResult(
    val status: TlsResult,
    val bytesConsumed: Int,
    val bytesProduced: Int,
)
