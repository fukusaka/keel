package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.io.KeelEofException

/**
 * Thrown when an unexpected end-of-stream is encountered while parsing
 * an HTTP message (request line, status line, or chunk size).
 */
public class HttpEofException(message: String) : KeelEofException(message)
