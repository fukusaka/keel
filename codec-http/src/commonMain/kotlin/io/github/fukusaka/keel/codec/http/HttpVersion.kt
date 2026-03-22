package io.github.fukusaka.keel.codec.http

/**
 * HTTP protocol version (RFC 7230 §2.6).
 * HTTP-version = "HTTP/" DIGIT "." DIGIT — case-sensitive, uppercase only.
 */
enum class HttpVersion(val text: String, val major: Int, val minor: Int) {
    HTTP_1_0("HTTP/1.0", 1, 0),
    HTTP_1_1("HTTP/1.1", 1, 1);

    companion object {
        fun of(text: String): HttpVersion =
            entries.firstOrNull { it.text == text }
                ?: throw IllegalArgumentException("Unsupported HTTP version: $text")
    }
}
