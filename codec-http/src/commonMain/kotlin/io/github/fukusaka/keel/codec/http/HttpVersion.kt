package io.github.fukusaka.keel.codec.http

/**
 * HTTP protocol version (RFC 7230 §2.6).
 * HTTP-version = "HTTP/" DIGIT "." DIGIT — case-sensitive, uppercase only.
 */
enum class HttpVersion(val text: String, val major: Int, val minor: Int) {
    HTTP_1_0("HTTP/1.0", 1, 0),
    HTTP_1_1("HTTP/1.1", 1, 1);

    companion object {
        /**
         * Returns the [HttpVersion] for [text], or throws [HttpParseException].
         *
         * Uses a `when` expression rather than `firstOrNull { }` to avoid allocating
         * a capturing lambda on every call (hot path: one call per HTTP request).
         */
        fun of(text: String): HttpVersion = when (text) {
            HTTP_1_1.text -> HTTP_1_1
            HTTP_1_0.text -> HTTP_1_0
            else -> throw HttpParseException("Unsupported HTTP version: $text")
        }
    }
}
