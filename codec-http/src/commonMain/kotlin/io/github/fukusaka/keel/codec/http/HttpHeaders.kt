package io.github.fukusaka.keel.codec.http

/**
 * HTTP header fields (RFC 7230 §3.2).
 *
 * - Field names are case-insensitive tokens (RFC 7230 §3.2).
 * - Insertion order is preserved; same-name fields keep their relative order (RFC 7230 §3.2.2).
 * - Set-Cookie must not be comma-joined (RFC 6265) — use getAll() + individual output.
 * - OWS (optional whitespace) in field values is stripped by the parser before storage.
 */
class HttpHeaders {

    private val entries = mutableListOf<Pair<String, String>>()

    /** Append a header field. Allows multiple values for the same name. */
    fun add(name: String, value: String): HttpHeaders {
        entries.add(name to value)
        return this
    }

    /** Replace all existing values for [name] with a single [value]. */
    operator fun set(name: String, value: String): HttpHeaders {
        entries.removeAll { it.first.equals(name, ignoreCase = true) }
        entries.add(name to value)
        return this
    }

    /** Returns the first value for [name] (case-insensitive), or null if absent. */
    operator fun get(name: String): String? =
        entries.firstOrNull { it.first.equals(name, ignoreCase = true) }?.second

    /** Returns all values for [name] (case-insensitive) in insertion order. */
    fun getAll(name: String): List<String> =
        entries.filter { it.first.equals(name, ignoreCase = true) }.map { it.second }

    /** Returns true if at least one field with [name] exists (case-insensitive). */
    operator fun contains(name: String): Boolean =
        entries.any { it.first.equals(name, ignoreCase = true) }

    /** Removes all fields with [name] (case-insensitive). */
    fun remove(name: String): HttpHeaders {
        entries.removeAll { it.first.equals(name, ignoreCase = true) }
        return this
    }

    /** Iterates all header fields in insertion order. */
    fun forEach(action: (name: String, value: String) -> Unit) =
        entries.forEach { (n, v) -> action(n, v) }

    // Convenience accessors

    /** Parsed value of the Content-Length header, or null if absent or malformed. */
    fun contentLength(): Long? = get(HttpHeaderName.CONTENT_LENGTH)?.trim()?.toLongOrNull()

    /** Value of the Content-Type header, or null if absent. */
    fun contentType(): String? = get(HttpHeaderName.CONTENT_TYPE)

    /** True if Transfer-Encoding is "chunked" (case-insensitive). */
    fun isChunked(): Boolean =
        get(HttpHeaderName.TRANSFER_ENCODING)?.trim().equals("chunked", ignoreCase = true)
}
