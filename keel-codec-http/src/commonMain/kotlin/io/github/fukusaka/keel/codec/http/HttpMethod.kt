package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.IoBuf

/**
 * HTTP request method (RFC 7231 §4, RFC 5789).
 * method = token — case-sensitive; custom extension methods are allowed by RFC,
 * so this is a data class rather than an enum.
 *
 * Safe methods (RFC 7231 §4.2.1):    GET, HEAD, OPTIONS, TRACE
 * Idempotent methods (RFC 7231 §4.2.2): GET, HEAD, PUT, DELETE, OPTIONS, TRACE
 */
data class HttpMethod(val name: String) {

    init {
        require(name.isNotEmpty()) { "Method name must not be empty" }
        require(name.none { it.isWhitespace() }) { "Method name must not contain whitespace: $name" }
    }

    val isSafe: Boolean get() = this in SAFE_METHODS
    val isIdempotent: Boolean get() = this in IDEMPOTENT_METHODS

    override fun toString() = name

    companion object {
        val GET = HttpMethod("GET")
        val HEAD = HttpMethod("HEAD")
        val POST = HttpMethod("POST")
        val PUT = HttpMethod("PUT")
        val DELETE = HttpMethod("DELETE")
        val CONNECT = HttpMethod("CONNECT")
        val OPTIONS = HttpMethod("OPTIONS")
        val TRACE = HttpMethod("TRACE")
        val PATCH = HttpMethod("PATCH") // RFC 5789

        /**
         * Returns a cached instance for standard HTTP methods, or creates
         * a new instance for extension methods. Avoids per-request allocation
         * for the common case (GET, POST, etc.).
         */
        fun of(name: String): HttpMethod = when (name) {
            "GET" -> GET
            "HEAD" -> HEAD
            "POST" -> POST
            "PUT" -> PUT
            "DELETE" -> DELETE
            "CONNECT" -> CONNECT
            "OPTIONS" -> OPTIONS
            "TRACE" -> TRACE
            "PATCH" -> PATCH
            else -> HttpMethod(name)
        }

        /**
         * Looks up a cached [HttpMethod] by ASCII byte range without allocating
         * an intermediate String.
         *
         * Returns one of the pre-cached constants ([GET], [HEAD], [POST], [PUT],
         * [DELETE], [CONNECT], [OPTIONS], [TRACE], [PATCH]) when the byte range
         * contains the corresponding uppercase ASCII token, or `null` otherwise.
         * The caller handles the `null` case by falling back to [of] with a
         * freshly allocated [String] for RFC 7231-compliant extension methods.
         *
         * Used by [io.github.fukusaka.keel.codec.http.HttpRequestDecoder] to
         * avoid per-request `substring` allocations on the hot path. The
         * [ByteArray] overload is used by the decoder's cross-IoBuf fallback
         * accumulator path; the [IoBuf] overload is used by the in-buffer fast
         * path.
         *
         * @param bytes ASCII byte range holding the method token.
         * @param offset Start of the token within [bytes].
         * @param length Token length in bytes.
         */
        internal fun fromBytesOrNull(bytes: ByteArray, offset: Int, length: Int): HttpMethod? =
            when (length) {
                3 -> match3Bytes(bytes, offset)
                4 -> match4Bytes(bytes, offset)
                5 -> match5Bytes(bytes, offset)
                6 -> if (matches(bytes, offset, "DELETE")) DELETE else null
                7 -> match7Bytes(bytes, offset)
                else -> null
            }

        /**
         * [IoBuf] overload of [fromBytesOrNull]. Reads the token bytes via
         * [IoBuf.getByte] (no [readerIndex][IoBuf.readerIndex] advance) and
         * returns the matching cached constant, or `null` for extension
         * methods.
         *
         * The caller must guarantee that `[offset, offset + length)` is
         * fully within `[readerIndex, writerIndex)` of [buf].
         *
         * @param buf Source buffer containing the token.
         * @param offset Absolute byte offset in [buf] where the token starts.
         * @param length Token length in bytes.
         */
        internal fun fromBytesOrNull(buf: IoBuf, offset: Int, length: Int): HttpMethod? =
            when (length) {
                3 -> match3Bytes(buf, offset)
                4 -> match4Bytes(buf, offset)
                5 -> match5Bytes(buf, offset)
                6 -> if (matches(buf, offset, "DELETE")) DELETE else null
                7 -> match7Bytes(buf, offset)
                else -> null
            }

        // Per-length helpers are split out so that the top-level when in
        // fromBytesOrNull stays under detekt's CyclomaticComplexMethod limit.

        private fun match3Bytes(bytes: ByteArray, off: Int): HttpMethod? = when {
            matches(bytes, off, "GET") -> GET
            matches(bytes, off, "PUT") -> PUT
            else -> null
        }

        private fun match4Bytes(bytes: ByteArray, off: Int): HttpMethod? = when {
            matches(bytes, off, "POST") -> POST
            matches(bytes, off, "HEAD") -> HEAD
            else -> null
        }

        private fun match5Bytes(bytes: ByteArray, off: Int): HttpMethod? = when {
            matches(bytes, off, "PATCH") -> PATCH
            matches(bytes, off, "TRACE") -> TRACE
            else -> null
        }

        private fun match7Bytes(bytes: ByteArray, off: Int): HttpMethod? = when {
            matches(bytes, off, "OPTIONS") -> OPTIONS
            matches(bytes, off, "CONNECT") -> CONNECT
            else -> null
        }

        private fun match3Bytes(buf: IoBuf, off: Int): HttpMethod? = when {
            matches(buf, off, "GET") -> GET
            matches(buf, off, "PUT") -> PUT
            else -> null
        }

        private fun match4Bytes(buf: IoBuf, off: Int): HttpMethod? = when {
            matches(buf, off, "POST") -> POST
            matches(buf, off, "HEAD") -> HEAD
            else -> null
        }

        private fun match5Bytes(buf: IoBuf, off: Int): HttpMethod? = when {
            matches(buf, off, "PATCH") -> PATCH
            matches(buf, off, "TRACE") -> TRACE
            else -> null
        }

        private fun match7Bytes(buf: IoBuf, off: Int): HttpMethod? = when {
            matches(buf, off, "OPTIONS") -> OPTIONS
            matches(buf, off, "CONNECT") -> CONNECT
            else -> null
        }

        private fun matches(bytes: ByteArray, off: Int, token: String): Boolean {
            for (i in 0 until token.length) {
                if (bytes[off + i].toInt() != token[i].code) return false
            }
            return true
        }

        private fun matches(buf: IoBuf, off: Int, token: String): Boolean {
            for (i in 0 until token.length) {
                if (buf.getByte(off + i).toInt() != token[i].code) return false
            }
            return true
        }

        private val SAFE_METHODS = setOf(GET, HEAD, OPTIONS, TRACE)
        private val IDEMPOTENT_METHODS = setOf(GET, HEAD, PUT, DELETE, OPTIONS, TRACE)
    }
}
