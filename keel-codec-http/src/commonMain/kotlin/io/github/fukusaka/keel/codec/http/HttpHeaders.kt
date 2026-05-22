package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.IoBufAsciiText
import io.github.fukusaka.keel.io.toDecLongOrNull

/**
 * HTTP header fields (RFC 7230 §3.2) — **L7-a-ii Variant (Y): IntArray-slot
 * storage with lazy allocation + small-N linear scan**.
 *
 * Storage model:
 * - `slots: IntArray?`, stride 5 per header
 *   (`[hashLower, nameStart, nameLen, valStart, valLen]`), allocated on the
 *   first entry. Range entries (`nameStart >= 0`) index the retained recv
 *   buffer [backing]; string entries (`nameStart == STRING_SENTINEL`, from
 *   `add` / `set` / cross-read fallback) index [stringBacking].
 * - `bucketHead` / `bucketNext`: a hash index built **only once the entry
 *   count exceeds [BUCKET_THRESHOLD]**. Below the threshold (the dominant
 *   response-header and small-request case) lookups linear-scan the slots
 *   and no bucket arrays are allocated.
 * - `backing: IoBuf?` — recv buffer retained for the lifetime of the range
 *   views; released on [resetForReuse] / [release].
 *
 * Defining property: the parse path (`addRange`) allocates **no per-header
 * heap object** — just int writes into pooled arrays; a view is
 * materialised lazily, only for the matched value, on [get]. A freshly
 * built header set (e.g. a response) with few fields allocates only the
 * `slots` array (right-sized) and a string store — no per-instance bucket
 * arrays. Static intern does not participate (no per-entry object to
 * share); its role reverts to HTTP/2 HPACK indexed-entry decode.
 *
 * Public API is CharSequence-first (BREAKING vs the historical `String`
 * API): [get] returns the view directly; [getString] materialises.
 */
class HttpHeaders {

    private var slots: IntArray? = null
    private var slotCount: Int = 0

    // Hash index, built lazily once slotCount exceeds BUCKET_THRESHOLD.
    // Both null below the threshold (linear-scan lookups).
    private var bucketHead: IntArray? = null
    private var bucketNext: IntArray? = null

    // Lazily allocated string store for non-range entries.
    private var stringBacking: ArrayList<String>? = null

    // Retained recv buffer backing every range entry's view (see addRange).
    private var backing: IoBuf? = null

    // Memoised `String` materialisation of range entries, parallel to the
    // slots, lazily allocated on the first String-returning access of a
    // range entry. Repeated String reads of the same header (the Ktor
    // adapter and other framework code read headers many times per
    // request) then return the cached `String` instead of re-materialising
    // a fresh view + `String` each time. String entries (responses) are
    // already `String`-backed and never populate these. The arrays are
    // retained across pooled reuse and cleared in [resetForReuse]. The
    // `CharSequence` [get] path stays uncached so a caller that only reads
    // `length` / a few chars off the view never pays a `String` copy.
    private var valueStringCache: Array<String?>? = null
    private var nameStringCache: Array<String?>? = null

    private var pooled: Boolean = false

    // --- Access ---

    /**
     * Returns the value for [name] as a [CharSequence]. For range entries
     * this materialises a single zero-copy [IoBufAsciiText] view over the
     * matched value; no allocation for un-matched entries. Use [getString]
     * when a `String` is required.
     */
    operator fun get(name: String): CharSequence? {
        if (slotCount == 0) return null
        val hash = caseInsensitiveHash(name)
        val matched = lastMatch(hash, name)
        return if (matched >= 0) valueOf(matched) else null
    }

    /**
     * [get] materialised to a `String` (or `null` if absent). The
     * materialised `String` of a range entry is memoised (see
     * [valueStringCache]) so repeated reads of the same header do not
     * re-allocate.
     */
    fun getString(name: String): String? {
        if (slotCount == 0) return null
        val hash = caseInsensitiveHash(name)
        val matched = lastMatch(hash, name)
        return if (matched >= 0) valueStringOf(matched) else null
    }

    fun getAll(name: String): List<String> {
        if (slotCount == 0) return emptyList()
        var result: MutableList<String>? = null
        for (i in 0 until slotCount) {
            if (nameMatches(i, name)) {
                (result ?: mutableListOf<String>().also { result = it }).add(valueStringOf(i))
            }
        }
        return result ?: emptyList()
    }

    operator fun contains(name: String): Boolean {
        if (slotCount == 0) return false
        val hash = caseInsensitiveHash(name)
        return lastMatch(hash, name) >= 0
    }

    val size: Int get() = slotCount
    val isEmpty: Boolean get() = slotCount == 0

    /**
     * Index of the last entry (in chain order = first inserted in wire
     * order, RFC 7230 §3.2.2) matching [name], or -1. Uses the hash index
     * when present, else a linear scan over the slots.
     */
    private fun lastMatch(hash: Int, name: String): Int {
        val s = slots ?: return -1
        val bh = bucketHead
        var matched = -1
        if (bh != null) {
            val bn = bucketNext ?: return -1
            var idx = bh[bucketOf(hash)]
            while (idx >= 0) {
                if (s[idx * STRIDE] == hash && nameMatches(idx, name)) matched = idx
                idx = bn[idx]
            }
        } else {
            // Forward scan: the first match is the first inserted (wire
            // order) — RFC 7230 §3.2.2 first-value semantic. The bucket
            // path reaches the same entry as the last match in its
            // reverse-insertion chain.
            for (i in 0 until slotCount) {
                if (s[i * STRIDE] == hash && nameMatches(i, name)) {
                    matched = i
                    break
                }
            }
        }
        return matched
    }

    // --- Entry accessors (view / string resolution) ---

    private fun nameOf(i: Int): CharSequence {
        val s = slotsOrFail()
        val base = i * STRIDE
        val ns = s[base + 1]
        if (ns == STRING_SENTINEL) return stringName(s[base + 2])
        return IoBufAsciiText(backingBuf(), ns, s[base + 2])
    }

    private fun valueOf(i: Int): CharSequence {
        val s = slotsOrFail()
        val base = i * STRIDE
        val ns = s[base + 1]
        if (ns == STRING_SENTINEL) return stringValue(s[base + 2])
        return IoBufAsciiText(backingBuf(), s[base + 3], s[base + 4])
    }

    /** Case-insensitive name compare without materialising the entry. */
    private fun nameMatches(i: Int, name: String): Boolean {
        val s = slotsOrFail()
        val base = i * STRIDE
        val ns = s[base + 1]
        if (ns == STRING_SENTINEL) return csEqualsIgnoreCase(stringName(s[base + 2]), name)
        return bufEqualsIgnoreCase(backingBuf(), ns, s[base + 2], name)
    }

    /** [valueOf] materialised to `String`, memoised for range entries. */
    private fun valueStringOf(i: Int): String {
        val s = slotsOrFail()
        val base = i * STRIDE
        if (s[base + 1] == STRING_SENTINEL) return stringValue(s[base + 2])
        val cache = ensureCache(valueStringCache)?.also { valueStringCache = it } ?: valueStringCache
        cache?.get(i)?.let { return it }
        val str = IoBufAsciiText(backingBuf(), s[base + 3], s[base + 4]).toString()
        cache?.set(i, str)
        return str
    }

    /** [nameOf] materialised to `String`, memoised for range entries. */
    private fun nameStringOf(i: Int): String {
        val s = slotsOrFail()
        val base = i * STRIDE
        val ns = s[base + 1]
        if (ns == STRING_SENTINEL) return stringName(s[base + 2])
        val cache = ensureCache(nameStringCache)?.also { nameStringCache = it } ?: nameStringCache
        cache?.get(i)?.let { return it }
        val str = IoBufAsciiText(backingBuf(), ns, s[base + 2]).toString()
        cache?.set(i, str)
        return str
    }

    /**
     * Returns a `String?[]` cache parallel to the slots, growing [existing]
     * to the current slot capacity (or allocating it). Returns `null` only
     * when there are no slots yet (no range entry can exist).
     */
    private fun ensureCache(existing: Array<String?>?): Array<String?>? {
        val capacity = (slots ?: return null).size / STRIDE
        return when {
            existing == null -> arrayOfNulls(capacity)
            existing.size < capacity -> existing.copyOf(capacity)
            else -> existing
        }
    }

    private fun stringName(strIdx: Int): String = stringStore()[strIdx]
    private fun stringValue(strIdx: Int): String = stringStore()[strIdx + 1]

    private fun stringStore(): ArrayList<String> =
        stringBacking ?: error("string entry without string backing")

    private fun backingBuf(): IoBuf =
        backing ?: error("range entry without backing buffer")

    private fun slotsOrFail(): IntArray =
        slots ?: error("entry access with no slots allocated")

    // --- Mutation ---

    fun add(name: String, value: String): HttpHeaders {
        val hash = caseInsensitiveHash(name)
        val sb = stringBacking ?: ArrayList<String>(INITIAL_ENTRY_CAPACITY * 2).also { stringBacking = it }
        val strIdx = sb.size
        sb.add(name)
        sb.add(value)
        appendSlot(hash, STRING_SENTINEL, strIdx, -1, -1)
        return this
    }

    /**
     * Adds a header whose name and value are byte ranges in [buf]
     * (Variant Y parse path). Writes five ints into [slots]; allocates no
     * per-header object. [buf] is retained on the first range-add and
     * released on [resetForReuse] / [release].
     */
    internal fun addRange(
        buf: IoBuf,
        hash: Int,
        nameStart: Int,
        nameLen: Int,
        valueStart: Int,
        valueLen: Int,
    ): HttpHeaders {
        val cur = backing
        if (cur != null && cur !== buf) {
            // Cross-read / second pipelined request reusing this instance:
            // materialise to detach from a buffer this instance does not
            // retain.
            add(
                IoBufAsciiText(buf, nameStart, nameLen).toString(),
                IoBufAsciiText(buf, valueStart, valueLen).toString(),
            )
            return this
        }
        if (cur == null) {
            backing = buf
            buf.retain()
        }
        appendSlot(hash, nameStart, nameLen, valueStart, valueLen)
        return this
    }

    private fun appendSlot(hash: Int, nameStart: Int, nameLen: Int, valStart: Int, valLen: Int) {
        val i = slotCount
        val s = ensureSlots(i + 1)
        val base = i * STRIDE
        s[base] = hash
        s[base + 1] = nameStart
        s[base + 2] = nameLen
        s[base + 3] = valStart
        s[base + 4] = valLen
        slotCount = i + 1
        val bh = bucketHead
        if (bh != null) {
            linkBucket(bh, i, hash)
        } else if (slotCount > BUCKET_THRESHOLD) {
            buildBuckets()
        }
    }

    /** Ensures [slots] can hold [neededEntries], allocating / growing it. */
    private fun ensureSlots(neededEntries: Int): IntArray {
        val cur = slots
        if (cur != null && cur.size >= neededEntries * STRIDE) return cur
        val newCount = when {
            cur == null -> maxOf(neededEntries, INITIAL_ENTRY_CAPACITY)
            else -> {
                var c = cur.size / STRIDE * 2
                while (c < neededEntries) c *= 2
                c
            }
        }
        val next = if (cur == null) IntArray(newCount * STRIDE) else cur.copyOf(newCount * STRIDE)
        slots = next
        // Keep bucketNext parallel to the slot capacity when bucketing.
        if (bucketHead != null) {
            val bn = bucketNext
            if (bn == null || bn.size < newCount) {
                bucketNext = bn?.copyOf(newCount) ?: IntArray(newCount)
            }
        }
        return next
    }

    /** Builds the hash index over the current entries (called past the threshold). */
    private fun buildBuckets() {
        val s = slots ?: return
        val head = IntArray(BUCKET_COUNT) { -1 }
        val next = IntArray(s.size / STRIDE)
        for (i in 0 until slotCount) {
            linkInto(head, next, i, s[i * STRIDE])
        }
        bucketHead = head
        bucketNext = next
    }

    private fun linkBucket(head: IntArray, i: Int, hash: Int) {
        val next = bucketNext ?: IntArray(slotsOrFail().size / STRIDE).also { bucketNext = it }
        linkInto(head, next, i, hash)
    }

    private fun linkInto(head: IntArray, next: IntArray, i: Int, hash: Int) {
        val bucket = bucketOf(hash)
        next[i] = head[bucket]
        head[bucket] = i
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
        if (slotCount == 0) return
        var anyRemoved = false
        for (i in 0 until slotCount) {
            if (nameMatches(i, name)) {
                anyRemoved = true
                break
            }
        }
        if (!anyRemoved) return
        // Rebuild slots / stringBacking from the surviving entries. Removal
        // is a cold path; the O(N) rebuild is fine.
        val src = slotsOrFail()
        val newSlots = IntArray(src.size)
        val newStrings = if (stringBacking != null) ArrayList<String>() else null
        var w = 0
        for (i in 0 until slotCount) {
            if (nameMatches(i, name)) continue
            val base = i * STRIDE
            val ns = src[base + 1]
            val wbase = w * STRIDE
            newSlots[wbase] = src[base]
            if (ns == STRING_SENTINEL) {
                val store = stringStore()
                val dst = newStrings ?: error("string entry without string backing")
                val srcIdx = src[base + 2]
                val newIdx = dst.size
                dst.add(store[srcIdx])
                dst.add(store[srcIdx + 1])
                newSlots[wbase + 1] = STRING_SENTINEL
                newSlots[wbase + 2] = newIdx
                newSlots[wbase + 3] = -1
                newSlots[wbase + 4] = -1
            } else {
                newSlots[wbase + 1] = ns
                newSlots[wbase + 2] = src[base + 2]
                newSlots[wbase + 3] = src[base + 3]
                newSlots[wbase + 4] = src[base + 4]
            }
            w++
        }
        slots = newSlots
        stringBacking = newStrings
        slotCount = w
        // Entry indices changed — the parallel String caches are now stale.
        valueStringCache = null
        nameStringCache = null
        // Drop or rebuild the hash index depending on the surviving count.
        if (w > BUCKET_THRESHOLD) {
            buildBuckets()
        } else {
            bucketHead = null
            bucketNext = null
        }
    }

    // --- Iteration ---

    fun forEach(action: (name: String, value: String) -> Unit) {
        for (i in 0 until slotCount) {
            action(nameStringOf(i), valueStringOf(i))
        }
    }

    fun names(): Set<String> {
        if (slotCount == 0) return emptySet()
        val result = linkedSetOf<String>()
        for (i in 0 until slotCount) {
            val n = nameStringOf(i)
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
        List(slotCount) { i -> nameStringOf(i) to valueStringOf(i) }

    fun nameAt(index: Int): String = nameStringOf(index)
    fun valueAt(index: Int): String = valueStringOf(index)

    internal fun getByLowercaseKey(key: String): String? = getString(key)

    val contentLength: Long? get() = getString(HttpHeaderName.CONTENT_LENGTH_KEY)?.trim()?.toDecLongOrNull()
    val contentType: String? get() = getString(HttpHeaderName.CONTENT_TYPE_KEY)
    val isChunked: Boolean
        get() = getString(HttpHeaderName.TRANSFER_ENCODING_KEY)?.contains("chunked", ignoreCase = true) == true
    val connection: String? get() = getString(HttpHeaderName.CONNECTION_KEY)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HttpHeaders) return false
        if (slotCount != other.slotCount) return false
        for (i in 0 until slotCount) {
            if (!csEqualsIgnoreCase(nameOf(i), other.nameOf(i))) return false
            if (!csContentEquals(valueOf(i), other.valueOf(i))) return false
        }
        return true
    }

    override fun hashCode(): Int {
        val s = slots ?: return 0
        var h = 0
        for (i in 0 until slotCount) {
            h = 31 * h + s[i * STRIDE]
            h = 31 * h + (1 shl 16)
            h = 31 * h + valueOf(i).hashCode()
            h = 31 * h + (1 shl 24)
        }
        return h
    }

    override fun toString(): String = buildString {
        append("HttpHeaders(")
        for (i in 0 until slotCount) {
            if (i > 0) append(", ")
            append(nameOf(i)).append(": ").append(valueOf(i))
        }
        append(")")
    }

    fun release() {
        if (!pooled) return
        resetForReuse()
        HttpHeadersPool.giveBack(this)
    }

    internal fun resetForReuse() {
        slotCount = 0
        // Keep the (pooled) slots / bucket arrays for the next borrower;
        // just clear the hash index if one was built.
        bucketHead?.fill(-1)
        stringBacking?.clear()
        // Release cached materialised Strings (they reference the prior
        // request's bytes); keep the arrays for the next borrower.
        valueStringCache?.fill(null)
        nameStringCache?.fill(null)
        // Release the recv buffer retained by [addRange] so views do not
        // outlive their backing bytes.
        backing?.release()
        backing = null
    }

    internal fun markPooled() {
        pooled = true
    }

    companion object {
        private const val STRIDE: Int = 5

        /** Sentinel in slot `nameStart` marking a string (non-range) entry. */
        private const val STRING_SENTINEL: Int = -1

        private const val BUCKET_LOG2: Int = 6
        private const val BUCKET_COUNT: Int = 1 shl BUCKET_LOG2
        private const val BUCKET_MASK: Int = BUCKET_COUNT - 1

        /**
         * Entry count above which a hash index ([bucketHead] / [bucketNext])
         * is built. At or below it, lookups linear-scan the slots — cheaper
         * for the small header sets that responses and minimal requests
         * carry, and it avoids the per-instance bucket-array allocation
         * entirely for those (decided by the end-to-end hot-path alloc
         * benchmark: the bucket arrays were the dominant fresh-instance
         * cost for response headers). Header-heavy requests (CDN / proxy,
         * N≫8) cross the threshold and get O(1) bucket lookups.
         */
        private const val BUCKET_THRESHOLD: Int = 8

        internal fun bucketOf(hash: Int): Int = hash and BUCKET_MASK

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

        /**
         * Case-insensitive name hash computed directly over an [IoBuf]
         * byte range (Variant Y parse path), matching [caseInsensitiveHash]
         * char-for-char for ASCII names.
         */
        internal fun caseInsensitiveHashOfBuf(buf: IoBuf, start: Int, length: Int): Int {
            var h = 0
            for (i in 0 until length) {
                val c = buf.getByte(start + i).toInt() and 0xFF
                val folded = if (c in 0x41..0x5A) c + 0x20 else c
                h = 31 * h + folded
            }
            return h
        }

        /** ASCII case-insensitive equality between two [CharSequence]s. */
        internal fun csEqualsIgnoreCase(a: CharSequence, b: CharSequence): Boolean {
            if (a.length != b.length) return false
            for (i in 0 until a.length) {
                var ca = a[i].code
                var cb = b[i].code
                if (ca in 0x41..0x5A) ca += 0x20
                if (cb in 0x41..0x5A) cb += 0x20
                if (ca != cb) return false
            }
            return true
        }

        /** Case-sensitive char-by-char equality between two [CharSequence]s. */
        internal fun csContentEquals(a: CharSequence, b: CharSequence): Boolean {
            if (a.length != b.length) return false
            for (i in 0 until a.length) {
                if (a[i] != b[i]) return false
            }
            return true
        }

        /**
         * ASCII case-insensitive equality of an [IoBuf] byte range against
         * a `String` — used by the name-compare hot path so the range
         * entry's name is not materialised into a view just to compare it.
         */
        private fun bufEqualsIgnoreCase(buf: IoBuf, start: Int, length: Int, s: String): Boolean {
            if (length != s.length) return false
            for (i in 0 until length) {
                var cb = buf.getByte(start + i).toInt() and 0xFF
                var cs = s[i].code
                if (cb in 0x41..0x5A) cb += 0x20
                if (cs in 0x41..0x5A) cs += 0x20
                if (cb != cs) return false
            }
            return true
        }
    }
}

/**
 * A single well-known HTTP header `(name, value)` pair held by
 * [StaticHeaderTable] (HPACK / QPACK static entries + H1 intern table).
 *
 * In the Variant Y storage model the per-request [HttpHeaders] no longer
 * uses `HeaderEntry`; it survives as the shape of the static / HPACK
 * table entries that Phase 13 (`keel-codec-http2`) decodes by index.
 */
internal class HeaderEntry(
    val hashLower: Int,
    val name: CharSequence,
    val value: CharSequence,
)
