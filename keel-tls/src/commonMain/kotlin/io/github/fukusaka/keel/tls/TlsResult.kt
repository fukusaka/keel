package io.github.fukusaka.keel.tls

/**
 * Result status from [TlsCodec.protect] and [TlsCodec.unprotect] operations.
 *
 * Terminology follows RFC 8446 (TLS 1.3) §5.2 "Record Payload Protection"
 * and RFC 9001 (QUIC-TLS) §5 "Packet Protection".
 */
enum class TlsResult {
    /** Operation completed successfully. Check [TlsCodecResult.bytesProduced]. */
    OK,

    /** Insufficient input data. Feed more ciphertext and retry [TlsCodec.unprotect]. */
    NEED_MORE_INPUT,

    /**
     * The TLS engine needs to send data (e.g., handshake response).
     *
     * Call [TlsCodec.protect] with an empty plaintext buffer to produce
     * the outgoing TLS record, then retry [TlsCodec.unprotect].
     */
    NEED_WRAP,

    /** Output buffer too small. Allocate a larger buffer and retry. */
    BUFFER_OVERFLOW,

    /** Peer sent close_notify or the connection is closed. */
    CLOSED,
}
