package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.io.toDecLongOrNull

/**
 * HTTP header fields (RFC 7230 §3.2).
 *
 * **Storage model** (L7-a-i, 2026-05-21): a flat `IntArray` slot table
 * (4 ints per header — name + value byte ranges) over a single
 * write-side `ByteArray` that holds all header name + value bytes in
 * insertion order. Heap `ByteArray` rather than `DirectByteBuffer` /
 * `IoBuf` deliberately — initial L7-a-i prototype used `IoBuf` for
 * forward compatibility with `IoBufAsciiText` but the resulting
 * `DirectByteBuffer` + Cleaner chain added a per-request Cleaner that
 * pushed GC time up enough to drop throughput by 21 % at single-thread
 * saturation (15.93 % → 23.2 % wall-clock GC fraction). Headers are
 * small, CPU-only data and never participate in zero-copy DMA paths,
 * so heap storage is the natural choice. The `IoBuf`-backed view
 * (`IoBufAsciiText`) joins the picture in L7-a-ii / L7-b where the
 * codec emits parsed header values as `CharSequence` ranges directly
 * over the parse buffer.
 *
 * Linear-scan lookup with ASCII case-folded compare; typical HTTP
 * requests carry 5–15 headers, so the absence of a hash table is a
 * deliberate trade for code simplicity (Ktor's `HttpHeadersMap` uses
 * open addressing for the up-to-128-header range; that range is rare
 * in production and the constant-factor advantage of a hash table is
 * offset by table init / hash compute for the common small-N case).
 *
 * - Field names are case-insensitive ASCII tokens (RFC 7230 §3.2).
 *   Stored bytes preserve the original case for HTTP/1.1 serialisation;
 *   the lookup path folds to lower-case before compare.
 * - Insertion order is preserved; same-name fields keep their relative
 *   order (RFC 7230 §3.2.2).
 * - Set-Cookie must not be comma-joined (RFC 6265) — use [getAll].
 * - OWS (optional whitespace) in field values is stripped by the
 *   parser before storage.
 *
 * **Public API**: unchanged from the previous `LinkedHashMap` × 2
 * implementation. Returns / accepts [String] throughout so existing
 * callers keep compiling. Per-access String allocation is the
 * trade-off for parse-time `LinkedHashMap.Entry` × N elimination;
 * net for the typical /hello GET (N=10 parsed, M=2-3 accessed) is a
 * win, see PR description for microbench numbers. A follow-up
 * L7-a-ii will change the public API to `CharSequence`-first for
 * zero per-access alloc.
 *
 * **Lifecycle**: instances allocate a `ByteArray` lazily on first
 * [add]; [release] clears the reference so the GC can reclaim it.
 * Calling [release] is optional — the array is reclaimed by ordinary
 * GC if the [HttpHeaders] instance is unreachable. [HttpHeaders.EMPTY]
 * is a singleton without a backing array and rejects mutation.
 */
class HttpHeaders private constructor(private val isEmptySingleton: Boolean) {

    constructor() : this(isEmptySingleton = false)

    // 4 ints per slot:
    //   [0] nameStart   — byte offset into [backing], inclusive
    //   [1] nameEnd     — byte offset, exclusive
    //   [2] valueStart
    //   [3] valueEnd
    // entryCount = number of populated slots (each slot represents one header value).
    private var slots: IntArray = EMPTY_SLOTS
    private var entryCount: Int = 0

    // Backing ByteArray holds all name + value bytes in insertion order.
    // Lazily allocated on the first mutation; grown when capacity is
    // exceeded. Heap storage rather than DirectByteBuffer / IoBuf —
    // headers are small CPU-only data and the Cleaner overhead of a
    // direct buffer dominates per-request GC time.
    private var backing: ByteArray? = null
    private var backingPos: Int = 0

    // Parallel-array iteration cache for [forEach] / [entries] / etc.
    // Materialises String per slot only on demand; invalidated on mutation.
    private var cachedFlatNames: Array<String>? = null
    private var cachedFlatValues: Array<String>? = null

    private fun invalidateCache() {
        cachedFlatNames = null
        cachedFlatValues = null
    }

    private fun ensureFlatCache() {
        if (cachedFlatNames != null) return
        val n = entryCount
        val names = Array(n) { i -> readString(slots[i * SLOT_INTS], slots[i * SLOT_INTS + 1]) }
        val values = Array(n) { i -> readString(slots[i * SLOT_INTS + 2], slots[i * SLOT_INTS + 3]) }
        cachedFlatNames = names
        cachedFlatValues = values
    }

    // --- Access ---

    /** Returns the first value for [name] (case-insensitive), or null if absent. */
    operator fun get(name: String): String? {
        val idx = findSlot(name) ?: return null
        return readString(slots[idx * SLOT_INTS + 2], slots[idx * SLOT_INTS + 3])
    }

    /** Returns all values for [name] (case-insensitive) in insertion order. */
    fun getAll(name: String): List<String> {
        if (entryCount == 0) return emptyList()
        var result: MutableList<String>? = null
        for (i in 0 until entryCount) {
            val nameStart = slots[i * SLOT_INTS]
            val nameEnd = slots[i * SLOT_INTS + 1]
            if (rangeEqualsIgnoreCase(nameStart, nameEnd, name)) {
                val v = readString(slots[i * SLOT_INTS + 2], slots[i * SLOT_INTS + 3])
                (result ?: mutableListOf<String>().also { result = it }).add(v)
            }
        }
        return result ?: emptyList()
    }

    /** Returns true if at least one field with [name] exists (case-insensitive). */
    operator fun contains(name: String): Boolean = findSlot(name) != null

    /** Total number of header field values (counting multi-valued headers individually). */
    val size: Int get() = entryCount

    /** True if no header fields are present. */
    val isEmpty: Boolean get() = entryCount == 0

    // --- Mutation ---

    /** Append a header field. Allows multiple values for the same name. */
    fun add(name: String, value: String): HttpHeaders {
        appendSlot(name, value)
        invalidateCache()
        return this
    }

    /** Replace all existing values for [name] with a single [value]. */
    operator fun set(name: String, value: String): HttpHeaders {
        // Remove all existing slots for `name`, then add the new value.
        removeAllSlots(name)
        appendSlot(name, value)
        invalidateCache()
        return this
    }

    /** Removes all fields with [name] (case-insensitive). */
    fun remove(name: String): HttpHeaders {
        if (removeAllSlots(name) > 0) invalidateCache()
        return this
    }

    // --- Iteration ---

    /**
     * Iterates all header fields in insertion order, preserving original
     * name case. Multi-valued headers yield one call per value.
     */
    fun forEach(action: (name: String, value: String) -> Unit) {
        if (entryCount == 0) return
        ensureFlatCache()
        val names = cachedFlatNames!!
        val values = cachedFlatValues!!
        for (i in 0 until entryCount) action(names[i], values[i])
    }

    /** Returns all unique header names in insertion order, preserving original case. */
    fun names(): Set<String> {
        if (entryCount == 0) return emptySet()
        val result = linkedSetOf<String>()
        ensureFlatCache()
        val names = cachedFlatNames!!
        for (i in 0 until entryCount) {
            val n = names[i]
            // Dedupe by lower-case key — but preserve the first-seen original case.
            var alreadySeen = false
            for (existing in result) {
                if (existing.equals(n, ignoreCase = true)) {
                    alreadySeen = true
                    break
                }
            }
            if (!alreadySeen) result.add(n)
        }
        return result
    }

    /** Returns all header fields as a list of (name, value) pairs, preserving original case. */
    fun entries(): List<Pair<String, String>> {
        if (entryCount == 0) return emptyList()
        ensureFlatCache()
        val names = cachedFlatNames!!
        val values = cachedFlatValues!!
        return List(entryCount) { i -> names[i] to values[i] }
    }

    // --- Indexed access (for suspend writer that cannot use inline forEach) ---

    /** Returns the name of the header at [index] (insertion order, original case). O(1). */
    fun nameAt(index: Int): String {
        require(index in 0 until entryCount) { "index $index out of bounds [0, $entryCount)" }
        ensureFlatCache()
        return cachedFlatNames!![index]
    }

    /** Returns the value of the header at [index] (insertion order). O(1). */
    fun valueAt(index: Int): String {
        require(index in 0 until entryCount) { "index $index out of bounds [0, $entryCount)" }
        ensureFlatCache()
        return cachedFlatValues!![index]
    }

    // --- Direct lookup (bypasses lowercase() allocation) ---

    /**
     * Returns the first value for a pre-lowered [key], or null if absent.
     *
     * Callers must pass a key that is already lowercase. This avoids the
     * [String.lowercase] allocation in [get] on the hot path. After the
     * L7 refactor the [get] path also avoids [String.lowercase] (the
     * compare is byte-level case folding), so this method is functionally
     * equivalent to [get] and retained only for source compatibility.
     */
    internal fun getByLowercaseKey(key: String): String? = get(key)

    // --- Typed properties ---

    /** Parsed value of the Content-Length header, or null if absent or malformed. */
    val contentLength: Long? get() = get(HttpHeaderName.CONTENT_LENGTH_KEY)?.trim()?.toDecLongOrNull()

    /** Value of the Content-Type header, or null if absent. */
    val contentType: String? get() = get(HttpHeaderName.CONTENT_TYPE_KEY)

    /** True if Transfer-Encoding contains "chunked" (case-insensitive). */
    val isChunked: Boolean
        get() = get(HttpHeaderName.TRANSFER_ENCODING_KEY)?.contains("chunked", ignoreCase = true) == true

    /** Value of the Connection header, or null if absent. */
    val connection: String? get() = get(HttpHeaderName.CONNECTION_KEY)

    // --- Equality / debugging ---

    /**
     * Equality is based on case-insensitive name + exact value compare
     * over the slot table.
     *
     * Two [HttpHeaders] instances with the same header values but
     * different original name casing (e.g. "Content-Type" vs "content-type")
     * are considered equal, since HTTP header names are case-insensitive
     * (RFC 7230 §3.2).
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpHeaders) return false
        if (entryCount != other.size) return false
        for (i in 0 until entryCount) {
            val nameStart = slots[i * SLOT_INTS]
            val nameEnd = slots[i * SLOT_INTS + 1]
            val nameLength = nameEnd - nameStart
            val valueStart = slots[i * SLOT_INTS + 2]
            val valueEnd = slots[i * SLOT_INTS + 3]
            val valueLength = valueEnd - valueStart
            // Compare against `other`'s slot at the same index — both
            // tables preserve insertion order so corresponding slots
            // describe the same logical header pair.
            val oNameStart = other.slots[i * SLOT_INTS]
            val oNameEnd = other.slots[i * SLOT_INTS + 1]
            val oNameLength = oNameEnd - oNameStart
            val oValueStart = other.slots[i * SLOT_INTS + 2]
            val oValueEnd = other.slots[i * SLOT_INTS + 3]
            val oValueLength = oValueEnd - oValueStart
            if (nameLength != oNameLength) return false
            if (valueLength != oValueLength) return false
            val thisBuf = backing
            val otherBuf = other.backing
            if (thisBuf == null || otherBuf == null) return false
            // Name compare: ASCII case-insensitive.
            for (j in 0 until nameLength) {
                val a = thisBuf[nameStart + j].toInt() and 0xFF
                val b = otherBuf[oNameStart + j].toInt() and 0xFF
                if (asciiToLower(a) != asciiToLower(b)) return false
            }
            // Value compare: byte-exact.
            for (j in 0 until valueLength) {
                if (thisBuf[valueStart + j] != otherBuf[oValueStart + j]) return false
            }
        }
        return true
    }

    override fun hashCode(): Int {
        var h = 0
        for (i in 0 until entryCount) {
            val nameStart = slots[i * SLOT_INTS]
            val nameEnd = slots[i * SLOT_INTS + 1]
            val valueStart = slots[i * SLOT_INTS + 2]
            val valueEnd = slots[i * SLOT_INTS + 3]
            val buf = backing ?: return 0
            // Name hash (case-insensitive)
            for (j in nameStart until nameEnd) {
                h = 31 * h + asciiToLower(buf[j].toInt() and 0xFF)
            }
            h = 31 * h + (1 shl 16) // separator between name and value
            for (j in valueStart until valueEnd) {
                h = 31 * h + (buf[j].toInt() and 0xFF)
            }
            h = 31 * h + (1 shl 24) // separator between entries
        }
        return h
    }

    override fun toString(): String = buildString {
        append("HttpHeaders(")
        ensureFlatCache()
        val names = cachedFlatNames
        val values = cachedFlatValues
        if (names != null && values != null) {
            for (i in 0 until entryCount) {
                if (i > 0) append(", ")
                append(names[i]).append(": ").append(values[i])
            }
        }
        append(")")
    }

    // --- Lifecycle ---

    /**
     * Releases the backing [IoBuf]. After release the instance must
     * not be read or mutated. Safe to call on
     * [HttpHeaders.EMPTY] and other instances that never allocated a
     * backing buffer (no-op).
     *
     * Optional: instances are otherwise garbage-collected. Callers that
     * explicitly want to return the backing buffer to the allocator pool
     * before GC (e.g. request-bound headers at the end of a handler)
     * should call this; production keel-server-http will wire it into
     * the request lifecycle in a follow-up PR.
     */
    fun release() {
        if (isEmptySingleton) return
        backing = null
        backingPos = 0
        slots = EMPTY_SLOTS
        entryCount = 0
        invalidateCache()
    }

    // --- Internal storage helpers ---

    /** Returns the slot index for the first matching name (case-insensitive), or null. */
    private fun findSlot(name: String): Int? {
        if (entryCount == 0) return null
        for (i in 0 until entryCount) {
            val nameStart = slots[i * SLOT_INTS]
            val nameEnd = slots[i * SLOT_INTS + 1]
            if (rangeEqualsIgnoreCase(nameStart, nameEnd, name)) return i
        }
        return null
    }

    /** ASCII case-insensitive compare of a backing byte range against [name]. */
    private fun rangeEqualsIgnoreCase(start: Int, end: Int, name: String): Boolean {
        val length = end - start
        if (length != name.length) return false
        val buf = backing ?: return length == 0
        for (i in 0 until length) {
            val b = buf[start + i].toInt() and 0xFF
            val c = name[i].code
            if (asciiToLower(b) != asciiToLower(c)) return false
        }
        return true
    }

    /** Materialise a [String] from a backing byte range, ISO-8859-1 / byte-as-char. */
    private fun readString(start: Int, end: Int): String {
        val length = end - start
        if (length == 0) return ""
        val buf = backing ?: return ""
        val chars = CharArray(length)
        for (i in 0 until length) {
            chars[i] = (buf[start + i].toInt() and 0xFF).toChar()
        }
        return chars.concatToString()
    }

    /** Append [name] and [value] as a new slot. Allocates backing on first call. */
    private fun appendSlot(name: String, value: String) {
        // Encode name + value as ISO-8859-1 (byte-as-char) — preserves
        // bytes 0x00-0xFF directly.
        val nameLen = name.length
        val valueLen = value.length
        ensureBackingCapacity(nameLen + valueLen)
        ensureSlotCapacity()
        val buf = backing!!
        val nameStart = backingPos
        writeIsoLatin1(buf, name, nameStart)
        val nameEnd = nameStart + nameLen
        val valueStart = nameEnd
        writeIsoLatin1(buf, value, valueStart)
        val valueEnd = valueStart + valueLen
        backingPos = valueEnd
        val slotBase = entryCount * SLOT_INTS
        slots[slotBase] = nameStart
        slots[slotBase + 1] = nameEnd
        slots[slotBase + 2] = valueStart
        slots[slotBase + 3] = valueEnd
        entryCount++
    }

    /** Remove every slot whose name matches [name] (case-insensitive). Returns count removed. */
    private fun removeAllSlots(name: String): Int {
        if (entryCount == 0) return 0
        var write = 0
        var removed = 0
        for (read in 0 until entryCount) {
            val nameStart = slots[read * SLOT_INTS]
            val nameEnd = slots[read * SLOT_INTS + 1]
            if (rangeEqualsIgnoreCase(nameStart, nameEnd, name)) {
                removed++
                continue
            }
            if (write != read) {
                slots[write * SLOT_INTS] = slots[read * SLOT_INTS]
                slots[write * SLOT_INTS + 1] = slots[read * SLOT_INTS + 1]
                slots[write * SLOT_INTS + 2] = slots[read * SLOT_INTS + 2]
                slots[write * SLOT_INTS + 3] = slots[read * SLOT_INTS + 3]
            }
            write++
        }
        entryCount = write
        // Note: backing bytes for removed slots are not reclaimed.
        // Production HTTP headers are typically append-only or set
        // once, so the cumulative waste from `remove` calls is bounded
        // by the per-request lifetime. A future optimisation could
        // compact the backing on remove if it proves problematic.
        return removed
    }

    private fun ensureBackingCapacity(needed: Int) {
        check(!isEmptySingleton) { "Cannot mutate HttpHeaders.EMPTY singleton" }
        val buf = backing
        if (buf == null) {
            val initial = maxOf(needed, INITIAL_BACKING_CAPACITY)
            backing = ByteArray(initial)
            return
        }
        if (buf.size - backingPos >= needed) return
        var newCap = buf.size
        while (newCap - backingPos < needed) newCap *= 2
        backing = buf.copyOf(newCap)
    }

    private fun ensureSlotCapacity() {
        val needed = (entryCount + 1) * SLOT_INTS
        if (slots.size >= needed) return
        var newSize = if (slots.isEmpty()) INITIAL_SLOT_CAPACITY * SLOT_INTS else slots.size * 2
        while (newSize < needed) newSize *= 2
        val newSlots = IntArray(newSize)
        if (entryCount > 0) slots.copyInto(newSlots, 0, 0, entryCount * SLOT_INTS)
        slots = newSlots
    }

    private fun writeIsoLatin1(buf: ByteArray, s: String, offset: Int) {
        // s.encodeToByteArray() would do UTF-8; we want byte-as-char
        // ISO-8859-1 for HTTP header semantics. Write each char's
        // low-byte directly.
        for (i in 0 until s.length) {
            buf[offset + i] = s[i].code.toByte()
        }
    }

    companion object {
        // 4 ints per slot. See `slots` field doc.
        internal const val SLOT_INTS: Int = 4

        // Default initial capacities. The bench-driven /hello workload
        // (1 Host on request, 3–4 auto-added response headers) consumes
        // ~80 bytes of name+value content per request side and 2 of 8
        // slots, so this sizing fits both sides without triggering
        // `ByteArray.copyOf` growth.
        //
        // PR #589 A/B at single-thread saturation (10 s window):
        //   capacity=128 → 350 k req/s   2786 B/req   15.68 % GC
        //                  (-6.6 % throughput vs 256 — growth event
        //                   fires on the response side, the
        //                   `copyOf` + transient garbage outweighs the
        //                   smaller initial alloc)
        //   capacity=192 → 361 k req/s   2914 B/req   15.37 % GC
        //                  (middle ground; growth occasionally fires)
        //   capacity=256 → 384 k req/s   3042 B/req   17.19 % GC
        //                  (no growth for /hello, highest throughput;
        //                   GC fraction trades up for the larger
        //                   per-request alloc, net throughput wins)
        //
        // For browser-typical workloads (5–15 headers, ~500–1000 bytes
        // name+value content) capacity=256 is undersized and will grow
        // 1–2 times per request. A configurable initial capacity is
        // future work — for now this is hard-coded to fit the /hello
        // bench-driven design baseline.
        private const val INITIAL_SLOT_CAPACITY: Int = 8
        private const val INITIAL_BACKING_CAPACITY: Int = 256

        private val EMPTY_SLOTS: IntArray = IntArray(0)

        /**
         * Shared empty instance with no header fields. Mutation throws
         * because the underlying singleton must not be mutated; callers
         * that need a writable empty instance must create one via the
         * primary constructor or [build].
         */
        val EMPTY: HttpHeaders = HttpHeaders(isEmptySingleton = true)

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

        private fun asciiToLower(c: Int): Int = if (c in 0x41..0x5A) c + 0x20 else c
    }
}
