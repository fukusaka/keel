package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.io.toDecLongOrNull

/**
 * HTTP header fields (RFC 7230 §3.2).
 *
 * **Storage model** (C2-v4 prototype, IntArray-indexed bucket):
 * - `ArrayList<HeaderEntry>` for insertion-order iteration (`entries`)
 * - `IntArray bucketHead[16]` = first entry index per hash bucket (-1 if empty)
 * - `IntArray bucketNext` = parallel to `entries`, next entry index in same bucket
 * - `HeaderEntry(name, value)` = 2 refs only, ~24 B per entry
 *
 * This achieves O(1) lookup via bucket index (like C2) with C
 * (list-of-entries)'s 24 B HeaderEntry size. The cost is the
 * bucketNext IntArray retained in the pool. Hash is recomputed on
 * every lookup (no per-entry stored hash); for typical header names
 * (10-15 chars) this is ~10-15 ns extra per lookup.
 */
class HttpHeaders private constructor(
    private val entries: ArrayList<HeaderEntry>,
    private val bucketHead: IntArray,
) {

    constructor() : this(ArrayList(INITIAL_ENTRY_CAPACITY), IntArray(BUCKET_COUNT).also { it.fill(-1) })

    // Parallel to `entries`: bucketNext[i] = next entry index in same
    // bucket as entries[i], or -1 if last in chain. Grown on demand.
    private var bucketNext: IntArray = IntArray(INITIAL_ENTRY_CAPACITY)

    private var pooled: Boolean = false

    // --- Access ---

    operator fun get(name: String): String? {
        val hash = caseInsensitiveHash(name)
        val bucket = hash and BUCKET_MASK
        var idx = bucketHead[bucket]
        var value: String? = null
        // Walk the full chain; bucket is prepended on add so chain is
        // reverse-insertion-order. The LAST match in the chain is the
        // FIRST inserted — Netty `DefaultHeaders.get` pattern. Hash
        // pre-check skips the per-byte equality on non-matching entries
        // in the bucket.
        while (idx >= 0) {
            val e = entries[idx]
            if (e.hashLower == hash && e.name.equals(name, ignoreCase = true)) value = e.value
            idx = bucketNext[idx]
        }
        return value
    }

    fun getAll(name: String): List<String> {
        if (entries.isEmpty()) return emptyList()
        var result: MutableList<String>? = null
        // Walk in insertion order via the entries ArrayList.
        for (i in entries.indices) {
            val e = entries[i]
            if (e.name.equals(name, ignoreCase = true)) {
                (result ?: mutableListOf<String>().also { result = it }).add(e.value)
            }
        }
        return result ?: emptyList()
    }

    operator fun contains(name: String): Boolean {
        val hash = caseInsensitiveHash(name)
        val bucket = hash and BUCKET_MASK
        var idx = bucketHead[bucket]
        while (idx >= 0) {
            val e = entries[idx]
            if (e.hashLower == hash && e.name.equals(name, ignoreCase = true)) return true
            idx = bucketNext[idx]
        }
        return false
    }

    val size: Int get() = entries.size
    val isEmpty: Boolean get() = entries.isEmpty()

    // --- Mutation ---

    fun add(name: String, value: String): HttpHeaders {
        val hash = caseInsensitiveHash(name)
        val bucket = hash and BUCKET_MASK
        val idx = entries.size
        entries.add(HeaderEntry(hash, name, value))
        ensureBucketNextCapacity(idx + 1)
        bucketNext[idx] = bucketHead[bucket]
        bucketHead[bucket] = idx
        return this
    }

    operator fun set(name: String, value: String): HttpHeaders {
        removeAll(name)
        add(name, value)
        return this
    }

    fun remove(name: String): HttpHeaders {
        removeAll(name)
        return this
    }

    private fun removeAll(name: String) {
        if (entries.isEmpty()) return
        // Snapshot the surviving entries, then rebuild bucketHead /
        // bucketNext from scratch. Simpler and fault-free vs in-place
        // re-index. Removal is a cold path; the O(N) rebuild is fine.
        var anyRemoved = false
        val kept = ArrayList<HeaderEntry>(entries.size)
        for (i in entries.indices) {
            val e = entries[i]
            if (e.name.equals(name, ignoreCase = true)) {
                anyRemoved = true
            } else {
                kept.add(e)
            }
        }
        if (!anyRemoved) return
        entries.clear()
        entries.addAll(kept)
        bucketHead.fill(-1)
        for (i in entries.indices) {
            val e = entries[i]
            val bucket = e.hashLower and BUCKET_MASK
            ensureBucketNextCapacity(i + 1)
            bucketNext[i] = bucketHead[bucket]
            bucketHead[bucket] = i
        }
    }

    private fun ensureBucketNextCapacity(needed: Int) {
        if (bucketNext.size >= needed) return
        var newSize = if (bucketNext.isEmpty()) INITIAL_ENTRY_CAPACITY else bucketNext.size * 2
        while (newSize < needed) newSize *= 2
        bucketNext = bucketNext.copyOf(newSize)
    }

    // --- Iteration ---

    fun forEach(action: (name: String, value: String) -> Unit) {
        for (i in entries.indices) {
            val e = entries[i]
            action(e.name, e.value)
        }
    }

    fun names(): Set<String> {
        if (entries.isEmpty()) return emptySet()
        val result = linkedSetOf<String>()
        for (i in entries.indices) {
            val n = entries[i].name
            var seen = false
            for (existing in result) {
                if (existing.equals(n, ignoreCase = true)) {
                    seen = true
                    break
                }
            }
            if (!seen) result.add(n)
        }
        return result
    }

    fun entries(): List<Pair<String, String>> =
        List(entries.size) { i -> entries[i].name to entries[i].value }

    fun nameAt(index: Int): String = entries[index].name
    fun valueAt(index: Int): String = entries[index].value

    internal fun getByLowercaseKey(key: String): String? = get(key)

    val contentLength: Long? get() = get(HttpHeaderName.CONTENT_LENGTH_KEY)?.trim()?.toDecLongOrNull()
    val contentType: String? get() = get(HttpHeaderName.CONTENT_TYPE_KEY)
    val isChunked: Boolean get() = get(HttpHeaderName.TRANSFER_ENCODING_KEY)?.contains("chunked", ignoreCase = true) == true
    val connection: String? get() = get(HttpHeaderName.CONNECTION_KEY)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpHeaders) return false
        if (entries.size != other.entries.size) return false
        for (i in entries.indices) {
            val a = entries[i]
            val b = other.entries[i]
            if (!a.name.equals(b.name, ignoreCase = true)) return false
            if (a.value != b.value) return false
        }
        return true
    }

    override fun hashCode(): Int {
        var h = 0
        for (i in entries.indices) {
            val e = entries[i]
            h = 31 * h + e.hashLower
            h = 31 * h + (1 shl 16)
            h = 31 * h + e.value.hashCode()
            h = 31 * h + (1 shl 24)
        }
        return h
    }

    override fun toString(): String = buildString {
        append("HttpHeaders(")
        for (i in entries.indices) {
            if (i > 0) append(", ")
            val e = entries[i]
            append(e.name).append(": ").append(e.value)
        }
        append(")")
    }

    fun release() {
        if (!pooled) return
        resetForReuse()
        HttpHeadersPool.giveBack(this)
    }

    internal fun resetForReuse() {
        entries.clear()
        bucketHead.fill(-1)
    }

    internal fun markPooled() {
        pooled = true
    }

    companion object {
        private const val BUCKET_COUNT: Int = 32
        private const val BUCKET_MASK: Int = BUCKET_COUNT - 1
        private const val INITIAL_ENTRY_CAPACITY: Int = 8

        val EMPTY: HttpHeaders = HttpHeaders()

        fun borrow(): HttpHeaders = HttpHeadersPool.borrow()

        fun build(block: HttpHeaders.() -> Unit): HttpHeaders = HttpHeaders().apply(block)

        fun of(vararg pairs: Pair<String, String>): HttpHeaders {
            val headers = HttpHeaders()
            for ((name, value) in pairs) headers.add(name, value)
            return headers
        }

        internal fun caseInsensitiveHash(s: String): Int {
            var h = 0
            for (i in 0 until s.length) {
                val c = s[i].code
                val folded = if (c in 0x41..0x5A) c + 0x20 else c
                h = 31 * h + folded
            }
            return h
        }
    }
}

internal class HeaderEntry(
    val hashLower: Int,
    val name: String,
    val value: String,
)
