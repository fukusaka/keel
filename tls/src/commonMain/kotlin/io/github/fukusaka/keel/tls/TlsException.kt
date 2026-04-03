package io.github.fukusaka.keel.tls

/**
 * Exception thrown by TLS operations (handshake, read, write, close).
 *
 * Wraps platform-specific error codes (e.g. OpenSSL error queue,
 * Mbed TLS error codes, JSSE SSLException) with a human-readable message.
 *
 * @param message Human-readable error description.
 * @param errorCode Platform-specific error code. 0 if not applicable.
 *                  Mbed TLS: negative error code (e.g. -0x7200).
 *                  OpenSSL: value from ERR_get_error().
 *                  JSSE: 0 (use [cause] for SSLException details).
 * @param cause Underlying platform exception, if any.
 */
class TlsException(
    message: String,
    val errorCode: Int = 0,
    cause: Throwable? = null,
) : Exception(message, cause)
