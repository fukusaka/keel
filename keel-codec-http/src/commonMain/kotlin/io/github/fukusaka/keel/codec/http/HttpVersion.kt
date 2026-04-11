package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.IoBuf

/**
 * HTTP protocol version (RFC 7230 §2.6).
 * HTTP-version = "HTTP/" DIGIT "." DIGIT — case-sensitive, uppercase only.
 */
enum class HttpVersion(val text: String, val major: Int, val minor: Int) {
    HTTP_1_0("HTTP/1.0", 1, 0),
    HTTP_1_1("HTTP/1.1", 1, 1),
    ;

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

        /**
         * Looks up the [HttpVersion] for an ASCII byte range without allocating
         * an intermediate String on the success path.
         *
         * Returns [HTTP_1_0] or [HTTP_1_1] when the byte range contains the
         * corresponding 8-byte uppercase ASCII token (`HTTP/1.0` / `HTTP/1.1`).
         * Any other content — including lowercase variants, versions other
         * than 1.x, and ranges whose length is not 8 — throws
         * [HttpParseException] with the same message format as [of].
         *
         * Used by [io.github.fukusaka.keel.codec.http.HttpRequestDecoder] on
         * the hot path to avoid per-request `substring` allocations. The error
         * path reconstructs the token as a [String] to keep the exception
         * message identical to [of]'s output.
         *
         * @param bytes ASCII byte range holding the version token.
         * @param offset Start of the token within [bytes].
         * @param length Token length in bytes.
         */
        internal fun fromBytes(bytes: ByteArray, offset: Int, length: Int): HttpVersion {
            if (length == 8 && matchesPrefix(bytes, offset)) {
                when (bytes[offset + 7].toInt()) {
                    '1'.code -> return HTTP_1_1
                    '0'.code -> return HTTP_1_0
                }
            }
            throw HttpParseException(
                "Unsupported HTTP version: ${bytes.decodeToString(offset, offset + length)}",
            )
        }

        /**
         * [IoBuf] overload of [fromBytes]. Reads the token bytes via
         * [IoBuf.getByte] (no [readerIndex][IoBuf.readerIndex] advance).
         *
         * The caller must guarantee that `[offset, offset + length)` is fully
         * within `[readerIndex, writerIndex)` of [buf].
         *
         * @param buf Source buffer containing the token.
         * @param offset Absolute byte offset in [buf] where the token starts.
         * @param length Token length in bytes.
         */
        internal fun fromBytes(buf: IoBuf, offset: Int, length: Int): HttpVersion {
            if (length == 8 && matchesPrefix(buf, offset)) {
                when (buf.getByte(offset + 7).toInt()) {
                    '1'.code -> return HTTP_1_1
                    '0'.code -> return HTTP_1_0
                }
            }
            val tmp = ByteArray(length)
            for (i in 0 until length) tmp[i] = buf.getByte(offset + i)
            throw HttpParseException("Unsupported HTTP version: ${tmp.decodeToString()}")
        }

        /**
         * Returns true if the 7 bytes starting at [offset] in [bytes] spell
         * the ASCII prefix `HTTP/1.`. Used by both [fromBytes] overloads to
         * avoid a 7-term && chain that trips detekt `ComplexCondition`.
         */
        private fun matchesPrefix(bytes: ByteArray, offset: Int): Boolean {
            for (i in 0 until HTTP_1_PREFIX.length) {
                if (bytes[offset + i].toInt() != HTTP_1_PREFIX[i].code) return false
            }
            return true
        }

        /** [IoBuf] overload of [matchesPrefix]. */
        private fun matchesPrefix(buf: IoBuf, offset: Int): Boolean {
            for (i in 0 until HTTP_1_PREFIX.length) {
                if (buf.getByte(offset + i).toInt() != HTTP_1_PREFIX[i].code) return false
            }
            return true
        }

        private const val HTTP_1_PREFIX = "HTTP/1."
    }
}
