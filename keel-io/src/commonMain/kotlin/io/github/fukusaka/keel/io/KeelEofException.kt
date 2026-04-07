package io.github.fukusaka.keel.io

/**
 * Thrown when an unexpected end-of-stream is reached during I/O operations.
 *
 * Subclassed by codec-specific EOF exceptions (e.g. `HttpEofException`)
 * to allow layered catch handling.
 */
public open class KeelEofException(message: String? = null) : Exception(message)
