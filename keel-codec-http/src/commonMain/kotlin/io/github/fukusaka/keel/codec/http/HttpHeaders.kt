package io.github.fukusaka.keel.codec.http

/**
 * HTTP header fields (RFC 7230 §3.2).
 *
 * Stores header values in a [LinkedHashMap] keyed by lowercase field name for O(1) lookup.
 * The original header name case is preserved for HTTP/1.1 serialization.
 *
 * - Field names are case-insensitive tokens (RFC 7230 §3.2).
 * - Insertion order is preserved; same-name fields keep their relative order (RFC 7230 §3.2.2).
 * - Set-Cookie must not be comma-joined (RFC 6265) — use [getAll] + individual output.
 * - OWS (optional whitespace) in field values is stripped by the parser before storage.
 */
class HttpHeaders private constructor(
    private val map: LinkedHashMap<String, MutableList<String>>,
    private val originalNames: LinkedHashMap<String, String>,
) {
    constructor() : this(LinkedHashMap(), LinkedHashMap())

    // Parallel arrays for O(1) indexed access without Pair allocation.
    // Invalidated on mutation; rebuilt on first access after mutation.
    private var flatNames: Array<String> = EMPTY_STRING_ARRAY
    private var flatValues: Array<String> = EMPTY_STRING_ARRAY
    private var flatValid = false

    private fun ensureFlatArrays() {
        if (flatValid) return
        var count = 0
        for ((_, values) in map) count += values.size
        val names = Array(count) { "" }
        val values = Array(count) { "" }
        var i = 0
        for ((key, vals) in map) {
            val name = originalNames[key] ?: key
            for (value in vals) {
                names[i] = name
                values[i] = value
                i++
            }
        }
        flatNames = names
        flatValues = values
        flatValid = true
    }

    private fun invalidateCache() {
        flatValid = false
    }

    // --- Access ---

    /** Returns the first value for [name] (case-insensitive), or null if absent. */
    operator fun get(name: String): String? = map[name.lowercase()]?.firstOrNull()

    /** Returns all values for [name] (case-insensitive) in insertion order. */
    fun getAll(name: String): List<String> = map[name.lowercase()] ?: emptyList()

    /** Returns true if at least one field with [name] exists (case-insensitive). */
    operator fun contains(name: String): Boolean = name.lowercase() in map

    /** Total number of header field values (counting multi-valued headers individually). */
    val size: Int get() {
        ensureFlatArrays()
        return flatNames.size
    }

    /** True if no header fields are present. */
    val isEmpty: Boolean get() = map.isEmpty()

    // --- Mutation ---

    /** Append a header field. Allows multiple values for the same name. */
    fun add(name: String, value: String): HttpHeaders {
        val key = name.lowercase()
        map.getOrPut(key) { mutableListOf() }.add(value)
        if (key !in originalNames) originalNames[key] = name
        invalidateCache()
        return this
    }

    /** Replace all existing values for [name] with a single [value]. */
    operator fun set(name: String, value: String): HttpHeaders {
        val key = name.lowercase()
        map[key] = mutableListOf(value)
        originalNames[key] = name
        invalidateCache()
        return this
    }

    /** Removes all fields with [name] (case-insensitive). */
    fun remove(name: String): HttpHeaders {
        val key = name.lowercase()
        map.remove(key)
        originalNames.remove(key)
        invalidateCache()
        return this
    }

    // --- Iteration ---

    /**
     * Iterates all header fields in insertion order, preserving original name case.
     *
     * Multi-valued headers yield one call per value.
     */
    fun forEach(action: (name: String, value: String) -> Unit) {
        ensureFlatArrays()
        for (i in flatNames.indices) {
            action(flatNames[i], flatValues[i])
        }
    }

    /** Returns all unique header names in insertion order, preserving original case. */
    fun names(): Set<String> {
        val result = linkedSetOf<String>()
        for ((key, _) in map) {
            result.add(originalNames[key] ?: key)
        }
        return result
    }

    /** Returns all header fields as a list of (name, value) pairs, preserving original case. */
    fun entries(): List<Pair<String, String>> {
        ensureFlatArrays()
        return List(flatNames.size) { i -> flatNames[i] to flatValues[i] }
    }

    // --- Indexed access (for suspend writer that cannot use inline forEach) ---

    /** Returns the name of the header at [index] (insertion order, original case). O(1). */
    fun nameAt(index: Int): String {
        ensureFlatArrays()
        return flatNames[index]
    }

    /** Returns the value of the header at [index] (insertion order). O(1). */
    fun valueAt(index: Int): String {
        ensureFlatArrays()
        return flatValues[index]
    }

    // --- Direct lookup (bypasses lowercase() allocation) ---

    /**
     * Returns the first value for a pre-lowered [key], or null if absent.
     *
     * Callers must pass a key that is already lowercase. This avoids the
     * [String.lowercase] allocation in [get] on the hot path.
     */
    internal fun getByLowercaseKey(key: String): String? = map[key]?.firstOrNull()

    // --- Typed properties ---

    /** Parsed value of the Content-Length header, or null if absent or malformed. */
    val contentLength: Long? get() = getByLowercaseKey(HttpHeaderName.CONTENT_LENGTH_KEY)?.trim()?.toLongOrNull()

    /** Value of the Content-Type header, or null if absent. */
    val contentType: String? get() = getByLowercaseKey(HttpHeaderName.CONTENT_TYPE_KEY)

    /** True if Transfer-Encoding contains "chunked" (case-insensitive). */
    val isChunked: Boolean
        get() = getByLowercaseKey(HttpHeaderName.TRANSFER_ENCODING_KEY)?.contains("chunked", ignoreCase = true) == true

    /** Value of the Connection header, or null if absent. */
    val connection: String? get() = getByLowercaseKey(HttpHeaderName.CONNECTION_KEY)

    /**
     * Equality is based on the normalized (lowercase) header map.
     *
     * Two [HttpHeaders] instances with the same header values but different original
     * name casing (e.g. "Content-Type" vs "content-type") are considered equal,
     * since HTTP header names are case-insensitive (RFC 7230 §3.2).
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpHeaders) return false
        return map == other.map
    }

    override fun hashCode(): Int = map.hashCode()

    override fun toString(): String = buildString {
        append("HttpHeaders(")
        val pairs = entries()
        pairs.forEachIndexed { i, (name, value) ->
            if (i > 0) append(", ")
            append("$name: $value")
        }
        append(")")
    }

    companion object {
        private val EMPTY_STRING_ARRAY = emptyArray<String>()

        /** Builds an [HttpHeaders] instance using the given [block]. */
        fun build(block: HttpHeaders.() -> Unit): HttpHeaders = HttpHeaders().apply(block)

        /** Creates an [HttpHeaders] from the given name-value [pairs]. */
        fun of(vararg pairs: Pair<String, String>): HttpHeaders {
            val headers = HttpHeaders()
            for ((name, value) in pairs) {
                headers.add(name, value)
            }
            return headers
        }
    }
}
