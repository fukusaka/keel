package io.github.fukusaka.keel.tls

/**
 * Exception thrown by TLS operations (handshake, protect, unprotect, close).
 *
 * Provides structured error information with a [category] for programmatic
 * handling and a [nativeErrorCode] for platform-specific debugging.
 *
 * @param message Human-readable error description.
 * @param category Broad error classification for programmatic handling.
 * @param nativeErrorCode Platform-specific error code for debugging.
 *        Mbed TLS: negative 32-bit code (e.g., -0x7200).
 *        OpenSSL/AWS-LC: value from `ERR_get_error()` (unsigned long).
 *        JSSE: 0 (use [cause] for `SSLException` details).
 * @param cause Underlying platform exception, if any.
 */
class TlsException(
    message: String,
    val category: TlsErrorCategory = TlsErrorCategory.UNKNOWN,
    val nativeErrorCode: Long = 0,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Broad classification of TLS errors for programmatic handling.
 *
 * Use [TlsException.nativeErrorCode] and [TlsException.cause] for
 * platform-specific diagnostics.
 */
enum class TlsErrorCategory {
    /** Handshake failed (protocol negotiation, cipher mismatch, etc.). */
    HANDSHAKE_FAILED,

    /** Certificate validation failed (expired, untrusted CA, hostname mismatch). */
    CERTIFICATE_INVALID,

    /** TLS protocol error (unexpected message, bad record MAC, etc.). */
    PROTOCOL_ERROR,

    /** Underlying I/O error during TLS operation. */
    IO_ERROR,

    /** Buffer allocation or capacity error. */
    BUFFER_ERROR,

    /** Connection closed (close_notify received or sent). */
    CLOSED,

    /** Error does not fit other categories. Check [TlsException.nativeErrorCode]. */
    UNKNOWN,
}
