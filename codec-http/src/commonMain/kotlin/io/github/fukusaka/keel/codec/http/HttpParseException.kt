package io.github.fukusaka.keel.codec.http

/**
 * Thrown when malformed HTTP syntax is encountered during parsing.
 *
 * Examples: invalid request/status line, unsupported HTTP version,
 * invalid chunk size, obs-fold in headers.
 */
public open class HttpParseException(message: String) : IllegalArgumentException(message)
