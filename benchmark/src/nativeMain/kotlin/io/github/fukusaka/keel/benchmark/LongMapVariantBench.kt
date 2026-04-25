package io.github.fukusaka.keel.benchmark

import kotlin.random.Random
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Variant matrix bench for primitive Long-keyed map design choices used by
 * `KqueueEventLoop` / `EpollEventLoop` registration tables.
 *
 * Axes:
 * - **encoding** (E1: high-bit-spread `fd | (interest shl 32)` — current keel,
 *   E2: dense low-bit `(fd shl 1) | interest`)
 * - **hash** (H1: identity `key.toInt()`, H2: xor-shift `(key xor (key ushr 32)).toInt()`,
 *   H3: Fibonacci `(mix * GOLDEN).toInt()`)
 * - **delete** (D1: tombstone, D2: backshift / Robin-Hood-style)
 * - **workload** (W1: read-dominant 9 get + 1 (rm+put), W2: write-churn 1 (rm+put))
 * - **size** (64 typical EL, 1024 stress)
 *
 * Workload key set: realistic engine fd table — fd-like dense ints with R+W
 * interleave (`fd 5 R, fd 5 W, fd 6 R, fd 6 W, ...`).
 *
 * Six concrete map classes (3 hash × 2 delete) avoid any indirect dispatch
 * during the hot loop; HashMap<Long, V> is the baseline.
 *
 * Invocation: `benchmark.kexe --bench=longmap-variants`
 */
fun runLongMapVariantBench() {
    println("LongObjectMap variant matrix bench (Kotlin/Native)")
    println("===================================================")
    println("variant|encoding|workload|size|ns/op|ops/sec")

    val sizes = intArrayOf(64, 1024)
    val encodings = listOf("E1-high32" to ::encodeHighBit, "E2-dense" to ::encodeDenseBit)
    val workloads = listOf("W1-read-dom" to true, "W2-write-churn" to false)

    val labels = listOf(
        "HashMap", "Tomb-Identity", "Tomb-XorShift", "Tomb-Fibonacci",
        "Back-Identity", "Back-XorShift", "Back-Fibonacci",
        "Tomb-FibUpper", "Back-FibUpper",
    )
    for (size in sizes) {
        for ((encName, encFn) in encodings) {
            val keys = generateKeys(size, encFn)
            for ((wlName, readDominant) in workloads) {
                for (label in labels) {
                    val ns = execTrial(mapFactory(label, size), keys, readDominant) / 10.0
                    val ops = 1_000_000_000.0 / ns
                    println("$label|$encName|$wlName|$size|${format1(ns)}|${formatE(ops)}")
                }
            }
        }
    }

    println()
    println("Pointer-key sub-bench (page-aligned, lookup-only — RegisteredBufferTable shape)")
    println("variant|encoding|workload|size|ns/op|ops/sec")
    val ptrSizes = intArrayOf(8, 16, 64)
    val ptrEncodings = listOf(
        "P-4K-aligned" to 4_096L,
        "P-64K-aligned" to 65_536L,
        "P-malloc-16B" to 16L, // typical malloc minimum alignment for small allocs
    )
    for (size in ptrSizes) {
        for ((pName, stride) in ptrEncodings) {
            val keys = generatePointerKeys(size, stride)
            for (label in labels) {
                val ns = execTrial(mapFactory(label, size), keys, readDominant = true, lookupOnly = true) / 10.0
                val ops = 1_000_000_000.0 / ns
                println("$label|$pName|W3-lookup-only|$size|${format1(ns)}|${formatE(ops)}")
            }
        }
    }
    println()
    println("blackhole=$variantBlackhole")
}

/**
 * Page-aligned pointer keys: simulates `nativeHeap.allocArray` returning
 * pointers spaced at [stride] bytes from a high base address. Stride 4096 /
 * 65536 = page-aligned (low 12 / 16 bits zero). Stride 16 = malloc minimum
 * alignment (low 4 bits zero), the most benign case.
 */
private fun generatePointerKeys(size: Int, stride: Long): LongArray {
    val base = 0x7F00_0000_0000L // realistic high-half userspace pointer
    return LongArray(size) { base + it * stride }
}

// -------------------------------------------------------------------------
// Key encoding (typical keel engine pattern: registrationKey(fd, interest))
// -------------------------------------------------------------------------

/** Current keel encoding: fd in low 32 bits, interest in upper 32 bits. */
private fun encodeHighBit(fd: Int, interest: Int): Long =
    fd.toLong() or (interest.toLong() shl 32)

/** Proposed encoding: dense low bits, `(fd shl 1) | interest`. */
private fun encodeDenseBit(fd: Int, interest: Int): Long =
    (fd.toLong() shl 1) or interest.toLong()

/**
 * Engine-realistic key set: fd 5..N+5, each with R+W registered. Keys are
 * interleaved (fd5R, fd5W, fd6R, fd6W, ...) — matches `EpollEventLoop` /
 * `KqueueEventLoop` typical TCP server pattern (read interest registered,
 * write interest registered when EAGAIN'd).
 */
private fun generateKeys(size: Int, enc: (Int, Int) -> Long): LongArray {
    val keys = LongArray(size)
    val fdBase = 5
    val numFds = size / 2
    for (i in 0 until numFds) {
        keys[i * 2] = enc(fdBase + i, 0)     // R
        keys[i * 2 + 1] = enc(fdBase + i, 1) // W
    }
    if (size % 2 == 1) keys[size - 1] = enc(fdBase + numFds, 0)
    return keys
}

// -------------------------------------------------------------------------
// Bench runner
// -------------------------------------------------------------------------

@kotlin.concurrent.Volatile
private var variantBlackhole: Long = 0

private const val WARMUP_MS = 1_500L
private const val TRIAL_MS = 3_000L

/** Executes warmup + 3-trial median for a given map factory + workload. Returns ns/cycle. */
private fun execTrial(
    factory: () -> LongMapAdapter,
    keys: LongArray,
    readDominant: Boolean,
    lookupOnly: Boolean = false,
): Double {
    val map = factory()
    for (k in keys) map.put(k, "v")

    // Warmup
    val warmupMark = TimeSource.Monotonic.markNow()
    var iters = 0L
    while (warmupMark.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < WARMUP_MS) {
        repeat(1_000) {
            if (lookupOnly) runLookupOnly(map, keys) else runMix(map, keys, readDominant)
            iters++
        }
    }
    variantBlackhole += iters

    // 3 trials, median
    val nsPerOp = DoubleArray(3)
    for (t in 0 until 3) {
        var trialIters = 0L
        val elapsed = measureTime {
            val deadline = TimeSource.Monotonic.markNow()
            while (deadline.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < TRIAL_MS) {
                repeat(10_000) {
                    if (lookupOnly) runLookupOnly(map, keys) else runMix(map, keys, readDominant)
                    trialIters++
                }
            }
        }
        nsPerOp[t] = elapsed.toDouble(DurationUnit.NANOSECONDS) / trialIters.toDouble()
    }
    nsPerOp.sort()
    return nsPerOp[1]
}

/**
 * Workload: 1 cycle = 10 ops.
 * - W1 (readDominant=true): 9 get + 1 (remove + put)
 * - W2 (readDominant=false): 10 (remove + put) — equivalent to current write-churn bench × 10
 *
 * Each cycle reports as 10 ops so ns/op = ns/cycle / 10.
 */
/** Lookup-only: 10 get per cycle. Used by RegisteredBufferTable.indexOf shape. */
private fun runLookupOnly(map: LongMapAdapter, keys: LongArray) {
    val size = keys.size
    var sum = 0L
    for (i in 0 until 10) {
        val k = keys[Random.nextInt(size)]
        val v = map.get(k)
        if (v != null) sum += k
    }
    variantBlackhole += sum
}

private fun runMix(map: LongMapAdapter, keys: LongArray, readDominant: Boolean) {
    val size = keys.size
    if (readDominant) {
        var sum = 0L
        for (i in 0 until 9) {
            val k = keys[Random.nextInt(size)]
            val v = map.get(k)
            if (v != null) sum += k
        }
        val k = keys[Random.nextInt(size)]
        map.remove(k)
        map.put(k, "v")
        variantBlackhole += sum
    } else {
        var sum = 0L
        for (i in 0 until 10) {
            val k = keys[Random.nextInt(size)]
            map.remove(k)
            map.put(k, "v")
            sum += k
        }
        variantBlackhole += sum
    }
}

private fun mapFactory(label: String, size: Int): () -> LongMapAdapter = when (label) {
    "HashMap" -> { -> HashMapAdapter(size * 2) }
    "Tomb-Identity" -> { -> TombIdentityMap(size * 2) }
    "Tomb-XorShift" -> { -> TombXorShiftMap(size * 2) }
    "Tomb-Fibonacci" -> { -> TombFibonacciMap(size * 2) }
    "Back-Identity" -> { -> BackIdentityMap(size * 2) }
    "Back-XorShift" -> { -> BackXorShiftMap(size * 2) }
    "Back-Fibonacci" -> { -> BackFibonacciMap(size * 2) }
    "Tomb-FibUpper" -> { -> TombFibUpperMap(size * 2) }
    "Back-FibUpper" -> { -> BackFibUpperMap(size * 2) }
    else -> error("unknown $label")
}

private fun format1(v: Double): String {
    val rounded = kotlin.math.round(v * 10.0) / 10.0
    return rounded.toString()
}

private fun formatE(v: Double): String {
    if (v <= 0.0) return "0"
    val exp = kotlin.math.floor(kotlin.math.log10(v)).toInt()
    var p = 1.0
    val absExp = if (exp < 0) -exp else exp
    repeat(absExp) { p *= 10.0 }
    val mantissa = if (exp < 0) v * p else v / p
    val mRound = kotlin.math.round(mantissa * 100.0) / 100.0
    return "${mRound}e${exp}"
}

// -------------------------------------------------------------------------
// Map adapter (one virtual call per op — same overhead across variants)
// -------------------------------------------------------------------------

private interface LongMapAdapter {
    fun put(key: Long, value: String)
    fun get(key: Long): String?
    fun remove(key: Long): String?
}

private class HashMapAdapter(initialCap: Int) : LongMapAdapter {
    private val map = HashMap<Long, String>(initialCap)
    override fun put(key: Long, value: String) { map[key] = value }
    override fun get(key: Long): String? = map[key]
    override fun remove(key: Long): String? = map.remove(key)
}

// -------------------------------------------------------------------------
// 6 concrete variants. Each owns parallel keys/values arrays. Hash function
// and delete strategy are inlined into the class to defeat any indirect
// dispatch. No resize implementation — bench pre-sizes via `initialCap`.
// -------------------------------------------------------------------------

private val TOMBSTONE: Any = Any()

/** Tombstone delete + identity hash. */
private class TombIdentityMap(initialCap: Int) : LongMapAdapter {
    private val cap = nextPow2(initialCap.coerceAtLeast(8))
    private val mask = cap - 1
    private val keys = LongArray(cap)
    private val values = arrayOfNulls<Any?>(cap)

    private fun hash(key: Long): Int = key.toInt() and mask

    override fun put(key: Long, value: String) {
        var i = hash(key)
        var firstTomb = -1
        while (true) {
            val s = values[i]
            when {
                s == null -> {
                    val target = if (firstTomb >= 0) firstTomb else i
                    keys[target] = key; values[target] = value; return
                }
                s === TOMBSTONE -> { if (firstTomb < 0) firstTomb = i }
                keys[i] == key -> { values[i] = value; return }
            }
            i = (i + 1) and mask
        }
    }

    override fun get(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (s !== TOMBSTONE && keys[i] == key) {
                @Suppress("UNCHECKED_CAST") return s as String
            }
            i = (i + 1) and mask
        }
    }

    override fun remove(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (s !== TOMBSTONE && keys[i] == key) {
                @Suppress("UNCHECKED_CAST") val old = s as String
                values[i] = TOMBSTONE
                return old
            }
            i = (i + 1) and mask
        }
    }
}

/** Tombstone delete + xor-shift hash. */
private class TombXorShiftMap(initialCap: Int) : LongMapAdapter {
    private val cap = nextPow2(initialCap.coerceAtLeast(8))
    private val mask = cap - 1
    private val keys = LongArray(cap)
    private val values = arrayOfNulls<Any?>(cap)

    private fun hash(key: Long): Int = ((key xor (key ushr 32)).toInt()) and mask

    override fun put(key: Long, value: String) {
        var i = hash(key)
        var firstTomb = -1
        while (true) {
            val s = values[i]
            when {
                s == null -> {
                    val target = if (firstTomb >= 0) firstTomb else i
                    keys[target] = key; values[target] = value; return
                }
                s === TOMBSTONE -> { if (firstTomb < 0) firstTomb = i }
                keys[i] == key -> { values[i] = value; return }
            }
            i = (i + 1) and mask
        }
    }

    override fun get(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (s !== TOMBSTONE && keys[i] == key) {
                @Suppress("UNCHECKED_CAST") return s as String
            }
            i = (i + 1) and mask
        }
    }

    override fun remove(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (s !== TOMBSTONE && keys[i] == key) {
                @Suppress("UNCHECKED_CAST") val old = s as String
                values[i] = TOMBSTONE
                return old
            }
            i = (i + 1) and mask
        }
    }
}

/** Tombstone delete + Fibonacci hash (current production). */
private class TombFibonacciMap(initialCap: Int) : LongMapAdapter {
    private val cap = nextPow2(initialCap.coerceAtLeast(8))
    private val mask = cap - 1
    private val keys = LongArray(cap)
    private val values = arrayOfNulls<Any?>(cap)

    private fun hash(key: Long): Int {
        val h = key xor (key ushr 32)
        return ((h * -0x61c8864680b583ebL).toInt()) and mask
    }

    override fun put(key: Long, value: String) {
        var i = hash(key)
        var firstTomb = -1
        while (true) {
            val s = values[i]
            when {
                s == null -> {
                    val target = if (firstTomb >= 0) firstTomb else i
                    keys[target] = key; values[target] = value; return
                }
                s === TOMBSTONE -> { if (firstTomb < 0) firstTomb = i }
                keys[i] == key -> { values[i] = value; return }
            }
            i = (i + 1) and mask
        }
    }

    override fun get(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (s !== TOMBSTONE && keys[i] == key) {
                @Suppress("UNCHECKED_CAST") return s as String
            }
            i = (i + 1) and mask
        }
    }

    override fun remove(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (s !== TOMBSTONE && keys[i] == key) {
                @Suppress("UNCHECKED_CAST") val old = s as String
                values[i] = TOMBSTONE
                return old
            }
            i = (i + 1) and mask
        }
    }
}

/** Backshift delete + identity hash. */
private class BackIdentityMap(initialCap: Int) : LongMapAdapter {
    private val cap = nextPow2(initialCap.coerceAtLeast(8))
    private val mask = cap - 1
    private val keys = LongArray(cap)
    private val values = arrayOfNulls<Any?>(cap)

    private fun hash(key: Long): Int = key.toInt() and mask

    override fun put(key: Long, value: String) {
        var i = hash(key)
        while (true) {
            val s = values[i]
            if (s == null) { keys[i] = key; values[i] = value; return }
            if (keys[i] == key) { values[i] = value; return }
            i = (i + 1) and mask
        }
    }

    override fun get(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (keys[i] == key) {
                @Suppress("UNCHECKED_CAST") return s as String
            }
            i = (i + 1) and mask
        }
    }

    override fun remove(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (keys[i] == key) {
                @Suppress("UNCHECKED_CAST") val old = s as String
                backshift(i)
                return old
            }
            i = (i + 1) and mask
        }
    }

    private fun backshift(removed: Int) {
        var i = removed
        while (true) {
            val j = (i + 1) and mask
            val s = values[j] ?: run { values[i] = null; return }
            val home = hash(keys[j])
            if (((j - home) and mask) >= ((j - i) and mask)) {
                keys[i] = keys[j]; values[i] = s
                i = j
            } else {
                values[i] = null
                return
            }
        }
    }
}

/** Backshift delete + xor-shift hash. */
private class BackXorShiftMap(initialCap: Int) : LongMapAdapter {
    private val cap = nextPow2(initialCap.coerceAtLeast(8))
    private val mask = cap - 1
    private val keys = LongArray(cap)
    private val values = arrayOfNulls<Any?>(cap)

    private fun hash(key: Long): Int = ((key xor (key ushr 32)).toInt()) and mask

    override fun put(key: Long, value: String) {
        var i = hash(key)
        while (true) {
            val s = values[i]
            if (s == null) { keys[i] = key; values[i] = value; return }
            if (keys[i] == key) { values[i] = value; return }
            i = (i + 1) and mask
        }
    }

    override fun get(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (keys[i] == key) {
                @Suppress("UNCHECKED_CAST") return s as String
            }
            i = (i + 1) and mask
        }
    }

    override fun remove(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (keys[i] == key) {
                @Suppress("UNCHECKED_CAST") val old = s as String
                backshift(i)
                return old
            }
            i = (i + 1) and mask
        }
    }

    private fun backshift(removed: Int) {
        var i = removed
        while (true) {
            val j = (i + 1) and mask
            val s = values[j] ?: run { values[i] = null; return }
            val home = hash(keys[j])
            if (((j - home) and mask) >= ((j - i) and mask)) {
                keys[i] = keys[j]; values[i] = s
                i = j
            } else {
                values[i] = null
                return
            }
        }
    }
}

/** Backshift delete + Fibonacci hash. */
private class BackFibonacciMap(initialCap: Int) : LongMapAdapter {
    private val cap = nextPow2(initialCap.coerceAtLeast(8))
    private val mask = cap - 1
    private val keys = LongArray(cap)
    private val values = arrayOfNulls<Any?>(cap)

    private fun hash(key: Long): Int {
        val h = key xor (key ushr 32)
        return ((h * -0x61c8864680b583ebL).toInt()) and mask
    }

    override fun put(key: Long, value: String) {
        var i = hash(key)
        while (true) {
            val s = values[i]
            if (s == null) { keys[i] = key; values[i] = value; return }
            if (keys[i] == key) { values[i] = value; return }
            i = (i + 1) and mask
        }
    }

    override fun get(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (keys[i] == key) {
                @Suppress("UNCHECKED_CAST") return s as String
            }
            i = (i + 1) and mask
        }
    }

    override fun remove(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (keys[i] == key) {
                @Suppress("UNCHECKED_CAST") val old = s as String
                backshift(i)
                return old
            }
            i = (i + 1) and mask
        }
    }

    private fun backshift(removed: Int) {
        var i = removed
        while (true) {
            val j = (i + 1) and mask
            val s = values[j] ?: run { values[i] = null; return }
            val home = hash(keys[j])
            if (((j - home) and mask) >= ((j - i) and mask)) {
                keys[i] = keys[j]; values[i] = s
                i = j
            } else {
                values[i] = null
                return
            }
        }
    }
}

private fun nextPow2(v: Int): Int {
    var x = 1
    while (x < v) x = x shl 1
    return x
}

/**
 * Tombstone delete + Fibonacci hash with **top-bit extraction** (matches K/N
 * stdlib HashMap). Take the high `log2(cap)` bits of the multiplication result,
 * not the low bits — low bits retain input pattern (e.g. all page-aligned
 * pointers collide at slot 0 with low-bit extraction).
 */
private class TombFibUpperMap(initialCap: Int) : LongMapAdapter {
    private val cap = nextPow2(initialCap.coerceAtLeast(8))
    private val mask = cap - 1
    private val shift = 64 - cap.countTrailingZeroBits() // top log2(cap) bits
    private val keys = LongArray(cap)
    private val values = arrayOfNulls<Any?>(cap)

    private fun hash(key: Long): Int {
        val h = key xor (key ushr 32)
        return ((h * -0x61c8864680b583ebL) ushr shift).toInt()
    }

    override fun put(key: Long, value: String) {
        var i = hash(key)
        var firstTomb = -1
        while (true) {
            val s = values[i]
            when {
                s == null -> {
                    val target = if (firstTomb >= 0) firstTomb else i
                    keys[target] = key; values[target] = value; return
                }
                s === TOMBSTONE -> { if (firstTomb < 0) firstTomb = i }
                keys[i] == key -> { values[i] = value; return }
            }
            i = (i + 1) and mask
        }
    }

    override fun get(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (s !== TOMBSTONE && keys[i] == key) {
                @Suppress("UNCHECKED_CAST") return s as String
            }
            i = (i + 1) and mask
        }
    }

    override fun remove(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (s !== TOMBSTONE && keys[i] == key) {
                @Suppress("UNCHECKED_CAST") val old = s as String
                values[i] = TOMBSTONE
                return old
            }
            i = (i + 1) and mask
        }
    }
}

/** Backshift delete + Fibonacci hash with top-bit extraction. */
private class BackFibUpperMap(initialCap: Int) : LongMapAdapter {
    private val cap = nextPow2(initialCap.coerceAtLeast(8))
    private val mask = cap - 1
    private val shift = 64 - cap.countTrailingZeroBits()
    private val keys = LongArray(cap)
    private val values = arrayOfNulls<Any?>(cap)

    private fun hash(key: Long): Int {
        val h = key xor (key ushr 32)
        return ((h * -0x61c8864680b583ebL) ushr shift).toInt()
    }

    override fun put(key: Long, value: String) {
        var i = hash(key)
        while (true) {
            val s = values[i]
            if (s == null) { keys[i] = key; values[i] = value; return }
            if (keys[i] == key) { values[i] = value; return }
            i = (i + 1) and mask
        }
    }

    override fun get(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (keys[i] == key) {
                @Suppress("UNCHECKED_CAST") return s as String
            }
            i = (i + 1) and mask
        }
    }

    override fun remove(key: Long): String? {
        var i = hash(key)
        while (true) {
            val s = values[i] ?: return null
            if (keys[i] == key) {
                @Suppress("UNCHECKED_CAST") val old = s as String
                backshift(i)
                return old
            }
            i = (i + 1) and mask
        }
    }

    private fun backshift(removed: Int) {
        var i = removed
        while (true) {
            val j = (i + 1) and mask
            val s = values[j] ?: run { values[i] = null; return }
            val home = hash(keys[j])
            if (((j - home) and mask) >= ((j - i) and mask)) {
                keys[i] = keys[j]; values[i] = s
                i = j
            } else {
                values[i] = null
                return
            }
        }
    }
}
