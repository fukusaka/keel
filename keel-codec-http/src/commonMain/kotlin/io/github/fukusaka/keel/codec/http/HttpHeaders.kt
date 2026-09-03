package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.IoBufAsciiText
import io.github.fukusaka.keel.buf.ioBufToLatin1String
import io.github.fukusaka.keel.io.parseDecLongAt
import io.github.fukusaka.keel.io.toDecLongOrNull

/**
 * HTTP header fields (RFC 7230 §3.2) — **IntArray-slot storage with lazy
 * allocation + small-N linear scan**.
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

    // Primary backing buffer (chain index 0) for range entries; retained on
    // the first [addRange] call. The common single-buffer case keeps the
    // [extraBackings] list `null` so the read path is byte-identical to the
    // pre-multi-segment state — see [bufFor].
    private var backing: IoBuf? = null

    // Lazy list of additional backing buffers (chain indices 1..N) populated
    // when a second distinct recv buffer contributes range entries on the
    // same [HttpHeaders] instance — the cross-read case. With every buffer
    // sharing a uniform 2^N capacity (enforced at config boundary by
    // `IoEngineConfig.readBufferSize` / `TlsServerConfig.plaintextBufferSize`),
    // range slots pack `(chainIndex shl [segmentLog2]) or posInSegment` into
    // the existing `nameStart` / `valueStart` ints so the slot stride does
    // not grow. A capacity mismatch (custom allocator / mixed pool classes)
    // bypasses the chain and falls back to `String` materialisation via the
    // existing side channel.
    private var extraBackings: ArrayList<IoBuf>? = null

    // `log2(buffer.capacity)` captured from the first [addRange] call. Used
    // to decode the packed `nameStart` / `valueStart` ints back into
    // `(chainIndex, posInSegment)` on the read path. `0` until set, which
    // is fine because the common single-buffer path never reads it (the
    // unpacked offset path short-circuits when [extraBackings] is null).
    private var segmentLog2: Int = 0

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

    // Sentinel guarding against a double [release]. Set true when the pool
    // hands the instance out ([markCheckedOut]) and cleared on the first
    // [release]; a second release sees false and no-ops. Without it a
    // double release would `giveBack` the same instance twice, leaving it
    // on the per-thread stack at two positions so two later borrowers
    // receive the same object and silently corrupt each other's headers.
    // Distinct from [pooled] (which means "participates in pooling" and
    // stays true across recycle) — this tracks the borrow/return cycle.
    private var checkedOut: Boolean = false

    // Caller-cache handle (Netty-Recycler style): the pool stack this instance
    // was borrowed from, recorded by [HttpHeadersPool.borrowFrom] so [release]
    // returns it without a per-call [headersPoolScope] lookup. Null when the
    // instance came from the plain [HttpHeadersPool.borrow] path, in which case
    // [HttpHeadersPool.giveBack] falls back to a lookup-at-release (the current
    // execution scope's stack) — always correct, for a borrow whose release
    // may resolve a different scope than the borrow did. Set only
    // for borrows taken on the connection's EventLoop scope, where capture-at-
    // borrow and lookup-at-release resolve to the same stack.
    internal var poolStack: ArrayDeque<HttpHeaders>? = null

    // --- Access ---

    /**
     * Returns the value for [name] as a [CharSequence]. For range entries
     * this materialises a single zero-copy [IoBufAsciiText] view over the
     * matched value; no allocation for un-matched entries. Use [getString]
     * when a `String` is required.
     *
     * Accepting any [CharSequence] lets callers pass a zero-copy view
     * (e.g. an [IoBufAsciiText] sourced from another header value or the
     * request URI's path slice) as the lookup key without materialising
     * it to `String` first. The case-insensitive name hash and compare
     * operate directly on the `CharSequence`.
     */
    operator fun get(name: CharSequence): CharSequence? {
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
    fun getString(name: CharSequence): String? {
        if (slotCount == 0) return null
        val hash = caseInsensitiveHash(name)
        val matched = lastMatch(hash, name)
        return if (matched >= 0) valueStringOf(matched) else null
    }

    fun getAll(name: CharSequence): List<String> {
        if (slotCount == 0) return emptyList()
        var result: MutableList<String>? = null
        for (i in 0 until slotCount) {
            if (nameMatches(i, name)) {
                (result ?: mutableListOf<String>().also { result = it }).add(valueStringOf(i))
            }
        }
        return result ?: emptyList()
    }

    /**
     * The combined value of every [name] field line, comma-joined in wire
     * order, or `null` when absent.
     *
     * For a **list-based** field (`#(...)` in the ABNF — `Accept`,
     * `Accept-Encoding`, `Vary`, …), RFC 9110 §5.3 makes multiple field
     * lines equivalent to one line with the values joined by `,` in receipt
     * order. A list-based-field parser must therefore read this rather than
     * a single line ([getString] returns only one occurrence — the first)
     * so it does not silently drop the field-values a client split across
     * lines.
     *
     * The common single-occurrence case allocates nothing (returns the
     * memoised value `String`); only a genuinely repeated field builds the
     * joined `String`.
     *
     * Not for singleton fields (`Content-Length`, `Host`, …) where repetition
     * is a protocol error rather than a list — use [getString] there.
     */
    fun getCombined(name: CharSequence): String? {
        if (slotCount == 0) return null
        var firstIdx = -1
        var joined: StringBuilder? = null
        for (i in 0 until slotCount) {
            if (!nameMatches(i, name)) continue
            if (firstIdx < 0) {
                firstIdx = i
            } else {
                val sb = joined ?: StringBuilder(valueStringOf(firstIdx)).also { joined = it }
                sb.append(", ").append(valueStringOf(i))
            }
        }
        return when {
            firstIdx < 0 -> null
            joined == null -> valueStringOf(firstIdx)
            else -> joined.toString()
        }
    }

    operator fun contains(name: CharSequence): Boolean {
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
    private fun lastMatch(hash: Int, name: CharSequence): Int {
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
        return IoBufAsciiText(bufFor(i), nameStartOf(i), s[base + 2])
    }

    private fun valueOf(i: Int): CharSequence {
        val s = slotsOrFail()
        val base = i * STRIDE
        val ns = s[base + 1]
        if (ns == STRING_SENTINEL) return stringValue(s[base + 2])
        return IoBufAsciiText(bufFor(i), valStartOf(i), s[base + 4])
    }

    /** Case-insensitive name compare without materialising the entry. */
    private fun nameMatches(i: Int, name: CharSequence): Boolean {
        val s = slotsOrFail()
        val base = i * STRIDE
        val ns = s[base + 1]
        if (ns == STRING_SENTINEL) return csEqualsIgnoreCase(stringName(s[base + 2]), name)
        return bufEqualsIgnoreCase(bufFor(i), nameStartOf(i), s[base + 2], name)
    }

    /** [valueOf] materialised to `String`, memoised for range entries. */
    private fun valueStringOf(i: Int): String {
        val s = slotsOrFail()
        val base = i * STRIDE
        if (s[base + 1] == STRING_SENTINEL) return stringValue(s[base + 2])
        val cache = ensureCache(valueStringCache)?.also { valueStringCache = it } ?: valueStringCache
        cache?.get(i)?.let { return it }
        val str = ioBufToLatin1String(bufFor(i), valStartOf(i), s[base + 4])
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
        val str = ioBufToLatin1String(bufFor(i), nameStartOf(i), s[base + 2])
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
     * [add] taking [CharSequence] for ergonomic symmetry with the lookup
     * API. The stored entry is still a `String` (mutation always
     * materialises) — the overload exists so callers holding a zero-copy
     * view (e.g. [IoBufAsciiText]) can hand it to [add] / [set] directly
     * without an explicit `.toString()`. Overload resolution prefers the
     * `String` overload for `String` arguments so existing call sites are
     * unchanged.
     */
    fun add(name: CharSequence, value: CharSequence): HttpHeaders = add(name.toString(), value.toString())

    /**
     * Pre-sizes storage for [entries] header fields so a builder adding a
     * statically known count allocates its slot array and string store at
     * exactly that size, instead of the [INITIAL_ENTRY_CAPACITY] floor the
     * first [add] would otherwise apply. The unpooled response path
     * ([HttpResponse.ok] etc.) builds a two-header `Content-Type` +
     * `Content-Length` set on every response, so without a hint each response
     * allocates an eight-slot `IntArray` and a sixteen-slot `ArrayList` for
     * two entries.
     *
     * No-op once storage exists or when [entries] is non-positive; adding
     * beyond [entries] grows normally. The string store is pre-allocated too
     * because the [build] / [of] callers that pass a hint use the `String`
     * [add] path (the buffer-range [addRange] path is the decoder's, which
     * pools instances instead of hinting).
     *
     * The hint is advisory: one large enough that `entries * STRIDE` would
     * overflow `Int` (wrapping to a negative [IntArray] size) is ignored and
     * the instance falls back to lazy growth, rather than throwing on what is
     * a caller sizing mistake.
     */
    internal fun reserve(entries: Int) {
        if (entries <= 0 || entries > MAX_RESERVE_ENTRIES || slots != null) return
        slots = IntArray(entries * STRIDE)
        if (stringBacking == null) stringBacking = ArrayList(entries * 2)
    }

    /**
     * Adds a header whose name and value are byte ranges in [buf] (the
     * parse path that never materialises a `String`). Writes five ints
     * into [slots] without
     * allocating any per-header object. The first range-add captures
     * `[segmentLog2] = log2(buf.capacity)` and retains [buf] as the primary
     * backing (chain index 0). A subsequent range-add with the same [buf]
     * is a no-op for the backing chain — only the slot is written. A
     * range-add with a different buffer of the **same uniform 2^N capacity**
     * registers that buffer as a chain extension (`extraBackings`), retains
     * it, and packs `(chainIndex shl segmentLog2) or posInSegment` into the
     * slot's name/value start ints so the slot stride stays at 5. Read-side
     * helpers ([bufFor] / [nameStartOf] / [valStartOf]) decode the packed
     * value back into `(chainIndex, posInSegment)`.
     *
     * A buffer whose capacity does not match the captured invariant (custom
     * allocator, non-power-of-two capacity, mismatched pool class) falls
     * back to `String` materialisation via [add] so chain-global addressing
     * never sees a non-uniform segment. In production the configured engine
     * allocators (PR #597 / #598) deliver uniform 2^N buffers and this
     * guard never fires; the fallback exists for tests, custom allocators,
     * and defensive correctness.
     *
     * All retained buffers are released on [resetForReuse] / [release].
     */
    internal fun addRange(
        buf: IoBuf,
        hash: Int,
        nameStart: Int,
        nameLen: Int,
        valueStart: Int,
        valueLen: Int,
    ): HttpHeaders {
        val chainIndex = chainIndexFor(buf)
        if (chainIndex < 0) {
            // Capacity guard rejected the buffer (non-power-of-two or mismatched
            // capacity vs the captured segment). Materialise to detach.
            add(
                IoBufAsciiText(buf, nameStart, nameLen).toString(),
                IoBufAsciiText(buf, valueStart, valueLen).toString(),
            )
            return this
        }
        val log2 = segmentLog2
        val packedNameStart = (chainIndex shl log2) or nameStart
        val packedValStart = (chainIndex shl log2) or valueStart
        appendSlot(hash, packedNameStart, nameLen, packedValStart, valueLen)
        return this
    }

    /**
     * Returns the chain index assigned to [buf], registering [buf] as the
     * primary backing (index 0) or as a new extra (index ≥ 1) when first
     * seen. Returns `-1` when [buf] cannot participate in the chain — its
     * capacity is not a power of two, or it differs from the segment size
     * captured on the first range-add.
     */
    private fun chainIndexFor(buf: IoBuf): Int {
        val cur = backing
        if (cur == null) {
            val cap = buf.capacity
            if (cap <= 0 || (cap and (cap - 1)) != 0) return -1
            // `Int.countTrailingZeroBits()` is `log2(cap)` for a power of
            // two — the same primitive used by `LongObjectMap.shift` and
            // `HttpResponseEncoder.size.countLeadingZeroBits / 4`.
            segmentLog2 = cap.countTrailingZeroBits()
            backing = buf
            buf.retain()
            return 0
        }
        if (cur === buf) return 0
        if (buf.capacity != cur.capacity) return -1
        val list = extraBackings ?: ArrayList<IoBuf>(2).also { extraBackings = it }
        for (i in list.indices) if (list[i] === buf) return i + 1
        list.add(buf)
        buf.retain()
        return list.size
    }

    /**
     * Resolves the backing [IoBuf] for slot [i]. Common single-buffer case
     * short-circuits on [extraBackings] being `null` so the read path is
     * byte-identical to the pre-multi-segment state.
     */
    private fun bufFor(i: Int): IoBuf {
        val extras = extraBackings ?: return backingBuf()
        val packed = slotsOrFail()[i * STRIDE + 1]
        val idx = packed ushr segmentLog2
        return if (idx == 0) backingBuf() else extras[idx - 1]
    }

    /** Decodes the packed `nameStart` int for slot [i] into the buffer-local offset. */
    private fun nameStartOf(i: Int): Int {
        val packed = slotsOrFail()[i * STRIDE + 1]
        val log2 = segmentLog2
        return if (log2 == 0 || extraBackings == null) packed else packed and ((1 shl log2) - 1)
    }

    /** Decodes the packed `valueStart` int for slot [i] into the buffer-local offset. */
    private fun valStartOf(i: Int): Int {
        val packed = slotsOrFail()[i * STRIDE + 3]
        val log2 = segmentLog2
        return if (log2 == 0 || extraBackings == null) packed else packed and ((1 shl log2) - 1)
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

    /** [set] taking [CharSequence] for symmetry with [add]; see [add] KDoc. */
    operator fun set(name: CharSequence, value: CharSequence): HttpHeaders = set(name.toString(), value.toString())

    fun remove(name: CharSequence): HttpHeaders {
        removeAll(name)
        return this
    }

    private fun removeAll(name: CharSequence) {
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

    /**
     * `Content-Length` parsed as a decimal [Long], or `null` when the header
     * is absent or not a valid decimal number.
     *
     * Buffer-backed slots (the decoder's [addRange] hot path) are parsed in
     * place with [parseDecLongAt] — no `String` and no [IoBufAsciiText] view
     * is allocated per request. String-backed entries ([add] / [set] callers)
     * keep the previous [String.trim] + [toDecLongOrNull] shape. Both parsers
     * share one digit-grammar/overflow core, and the in-place path trims with
     * the same [Char.isWhitespace] predicate as [String.trim], so the result
     * is identical to the former `getString(...)?.trim()?.toDecLongOrNull()`.
     */
    val contentLength: Long?
        get() {
            if (slotCount == 0) return null
            val key = HttpHeaderName.CONTENT_LENGTH_KEY
            val i = lastMatch(caseInsensitiveHash(key), key)
            if (i < 0) return null
            return parseContentLengthAt(i)
        }

    /**
     * In-place decimal parse of the `Content-Length` value stored at slot [i],
     * or `null` when the value is not a valid decimal number. Buffer-backed
     * slots are parsed with [parseDecLongAt] (no `String` allocation);
     * String-backed entries use the [String.trim] + [toDecLongOrNull] shape.
     */
    private fun parseContentLengthAt(i: Int): Long? {
        val s = slotsOrFail()
        val base = i * STRIDE
        if (s[base + 1] == STRING_SENTINEL) return stringValue(s[base + 2]).trim().toDecLongOrNull()
        // In-place parse of the stored value range. The decoder stores
        // OWS-trimmed ranges already; re-trim defensively so an addRange
        // caller that did not pre-trim gets the String-path semantics.
        val buf = bufFor(i)
        var start = valStartOf(i)
        var end = start + s[base + 4]
        while (start < end && isTrimmedByte(buf.getByte(start))) start++
        while (end > start && isTrimmedByte(buf.getByte(end - 1))) end--
        return buf.parseDecLongAt(start, end - start)
    }

    /**
     * True when the `Content-Length` value at slot [i], after OWS trim, begins
     * with a sign (`+` / `-`). [parseContentLengthAt] would accept such a value
     * as a number, but RFC 9110 §8.6 grammar is `Content-Length = 1*DIGIT`, so
     * a signed value is malformed framing and must be rejected.
     */
    private fun contentLengthHasLeadingSign(i: Int): Boolean {
        val s = slotsOrFail()
        val base = i * STRIDE
        if (s[base + 1] == STRING_SENTINEL) {
            val v = stringValue(s[base + 2]).trim()
            return v.isNotEmpty() && (v[0] == '+' || v[0] == '-')
        }
        val buf = bufFor(i)
        var start = valStartOf(i)
        val end = start + s[base + 4]
        while (start < end && isTrimmedByte(buf.getByte(start))) start++
        if (start >= end) return false
        val b = buf.getByte(start).toInt() and 0xFF
        return b == '+'.code || b == '-'.code
    }

    /**
     * Validates the message's `Content-Length` header field(s) against the
     * unrecoverable-framing hazards of RFC 9110 §8.6 / RFC 9112 §6.3:
     *
     * - a `Content-Length` present with a value that is not `1*DIGIT` —
     *   **unparseable** (`5x`, empty), **signed** (`+5` / `-5`), or overflowing
     *   `Long` — which [contentLength] would silently report as absent (or, for
     *   a sign, parse to a value that mis-frames the body); and
     * - **two or more `Content-Length` fields with differing values**, which
     *   [contentLength] silently collapses to the first wire-order value.
     *
     * Returns [ContentLengthValidity.INVALID] for either; [ABSENT] when no
     * `Content-Length` is present; [VALID] when a single value (or several
     * identical duplicates, permitted by RFC 9110 §8.6) is present. A message
     * decoder rejects an INVALID result rather than mis-framing the body and
     * letting its bytes be parsed as the next message (request/response
     * splitting).
     *
     * Rejecting a **signed** value here closes the negative-`Content-Length`
     * splitting vector for both decoders through this one shared gate, so it no
     * longer depends on each decoder carrying its own negative guard. A single
     * in-place slot scan that materialises no intermediate list; the
     * buffer-backed decoder path ([addRange]) is fully allocation-free, and the
     * String-backed path may `trim` one substring per `Content-Length` field
     * that carries OWS.
     */
    fun contentLengthValidity(): ContentLengthValidity {
        if (slotCount == 0) return ContentLengthValidity.ABSENT
        val hash = caseInsensitiveHash(HttpHeaderName.CONTENT_LENGTH_KEY)
        val s = slotsOrFail()
        var seen = false
        var firstValue = 0L
        for (i in 0 until slotCount) {
            if (s[i * STRIDE] != hash || !nameMatches(i, HttpHeaderName.CONTENT_LENGTH_KEY)) continue
            // 1*DIGIT only: reject a signed value that parseContentLengthAt would
            // otherwise accept, and an unparseable / overflowing value (null).
            if (contentLengthHasLeadingSign(i)) return ContentLengthValidity.INVALID
            val v = parseContentLengthAt(i) ?: return ContentLengthValidity.INVALID
            if (!seen) {
                seen = true
                firstValue = v
            } else if (v != firstValue) {
                return ContentLengthValidity.INVALID
            }
        }
        return if (seen) ContentLengthValidity.VALID else ContentLengthValidity.ABSENT
    }

    val contentType: String? get() = getString(HttpHeaderName.CONTENT_TYPE_KEY)

    /**
     * Whether `Transfer-Encoding` contains a `chunked` token. Reads the value
     * through the [get] view (no `String` materialisation on the decoder's
     * buffer-backed hot path); [CharSequence.contains] with `ignoreCase`
     * matches the previous `getString(...)` semantics character-for-character.
     */
    val isChunked: Boolean
        get() = get(HttpHeaderName.TRANSFER_ENCODING_KEY)?.contains("chunked", ignoreCase = true) == true

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
        if (!pooled || !checkedOut) return
        checkedOut = false
        resetForReuse()
        HttpHeadersPool.giveBack(this)
    }

    /**
     * Test-only accessor: number of slots that hold byte-range entries
     * (`nameStart != [STRING_SENTINEL]`). With chain-global multi-segment
     * addressing a cross-read parse over uniform 2^N buffers leaves every
     * header as a range entry, so this matches [size]; a fall back to
     * `String` materialisation (capacity guard mismatch, or the historical
     * pre-chain-global code path) reduces the count by every fallback slot.
     */
    internal val rangeEntryCount: Int
        get() {
            if (slotCount == 0) return 0
            val s = slots ?: return 0
            var n = 0
            for (i in 0 until slotCount) {
                if (s[i * STRIDE + 1] != STRING_SENTINEL) n++
            }
            return n
        }

    /**
     * Capacity (in slot positions, not ints) of the underlying [slots]
     * array — the high-water mark of `add` / `addRange` calls this
     * instance has serviced and grown its storage to. Read by
     * [HttpHeadersPool] to refuse to recycle an instance that grew
     * past the pool's shrink threshold, so a single oversized request
     * does not poison the per-thread pool with arrays many times the
     * typical request's footprint.
     */
    internal val slotCapacity: Int
        get() = (slots?.size ?: 0) / STRIDE

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
        // Release every recv buffer retained by [addRange] / [chainIndexFor]
        // so range-entry views do not outlive their backing bytes. Both the
        // primary [backing] and every chain extension in [extraBackings]
        // were retained with `buf.retain()` at registration time.
        backing?.release()
        backing = null
        extraBackings?.let { extras ->
            for (i in extras.indices) extras[i].release()
            extras.clear()
        }
        segmentLog2 = 0
    }

    internal fun markPooled() {
        pooled = true
    }

    /**
     * Marks the instance as checked out of the pool (borrowed, not yet
     * released). Called by [HttpHeadersPool.borrow] on every hand-out so
     * the first [release] returns it exactly once and a double release
     * no-ops. See [checkedOut].
     */
    internal fun markCheckedOut() {
        checkedOut = true
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

        /**
         * Largest [reserve] hint that does not overflow the slot [IntArray]
         * (`entries * STRIDE`). A hint above this is treated as absent so the
         * advisory pre-sizing never throws [NegativeArraySizeException] on a
         * caller sizing mistake.
         */
        private const val MAX_RESERVE_ENTRIES: Int = Int.MAX_VALUE / STRIDE

        val EMPTY: HttpHeaders = HttpHeaders()

        fun borrow(): HttpHeaders = HttpHeadersPool.borrow()

        fun build(block: HttpHeaders.() -> Unit): HttpHeaders = HttpHeaders().apply(block)

        /**
         * [build] that pre-sizes storage for [expectedEntries] header fields
         * (see [reserve]) — for the unpooled response-construction path where
         * the field count is statically known (2 for a typical `Content-Type`
         * + `Content-Length` response), so it does not over-allocate the slot
         * array and string store to the [INITIAL_ENTRY_CAPACITY] default.
         * Adding more than [expectedEntries] inside [block] grows normally.
         */
        fun build(expectedEntries: Int, block: HttpHeaders.() -> Unit): HttpHeaders =
            HttpHeaders().also { it.reserve(expectedEntries) }.apply(block)

        fun of(vararg pairs: Pair<String, String>): HttpHeaders {
            val headers = HttpHeaders()
            headers.reserve(pairs.size)
            for ((name, value) in pairs) headers.add(name, value)
            return headers
        }

        /**
         * Case-insensitive name hash (ASCII A..Z folded to a..z, polynomial
         * 31 mixing) over any [CharSequence] — `String`, [IoBufAsciiText],
         * `StringBuilder`, etc. Matches [caseInsensitiveHashOfBuf]
         * char-for-char for ASCII so a name parsed straight off an
         * [IoBuf] hashes identically to its `String` materialisation;
         * [HttpHeadersHashInvariantTest] pins this equivalence so a
         * divergence would be caught immediately.
         */
        internal fun caseInsensitiveHash(s: CharSequence): Int {
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
         * byte range (the range-add parse path), matching
         * [caseInsensitiveHash] char-for-char for ASCII names.
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

        /**
         * Whether [String.trim] would strip this Latin-1 decoded byte —
         * the exact [Char.isWhitespace] predicate, so the in-place
         * [contentLength] trim matches the String-path `.trim()`
         * byte-for-byte.
         */
        private fun isTrimmedByte(b: Byte): Boolean = (b.toInt() and 0xFF).toChar().isWhitespace()

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
         * any [CharSequence] — used by the name-compare hot path so the
         * range entry's name is not materialised into a view just to
         * compare it. Accepts `CharSequence` so a caller already holding a
         * zero-copy view (e.g. [IoBufAsciiText]) can be used directly as
         * the lookup key.
         */
        private fun bufEqualsIgnoreCase(buf: IoBuf, start: Int, length: Int, s: CharSequence): Boolean {
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
 * Result of [HttpHeaders.contentLengthValidity] — whether a message's
 * `Content-Length` header field(s) are well-formed for body framing.
 *
 * - [ABSENT]: no `Content-Length` header is present.
 * - [VALID]: a single value (or several identical duplicates) is present.
 * - [INVALID]: a value is unparseable, or two fields carry differing values —
 *   an unrecoverable framing error the caller must reject (RFC 9110 §8.6 /
 *   RFC 9112 §6.3).
 */
enum class ContentLengthValidity { ABSENT, VALID, INVALID }

/**
 * A single well-known HTTP header `(name, value)` pair held by
 * [StaticHeaderTable] (HPACK / QPACK static entries + H1 intern table).
 *
 * Under byte-range storage the per-request [HttpHeaders] no longer uses
 * `HeaderEntry`; it survives as the shape of the static / HPACK
 * table entries that a future `keel-codec-http2` will decode by index.
 */
internal class HeaderEntry(
    val hashLower: Int,
    val name: CharSequence,
    val value: CharSequence,
)
