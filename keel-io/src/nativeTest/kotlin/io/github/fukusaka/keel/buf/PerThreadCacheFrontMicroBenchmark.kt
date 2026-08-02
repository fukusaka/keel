@file:OptIn(UnsafeIoBufApi::class)

package io.github.fukusaka.keel.buf

import io.github.fukusaka.keel.scope.ScopeLocal
import io.github.fukusaka.keel.scope.ThreadLocalScopeLocal
import io.github.fukusaka.keel.scope.scopeLocal
import platform.posix.pthread_equal
import platform.posix.pthread_self
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.native.concurrent.ThreadLocal
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.time.TimeSource

/**
 * Pooled-allocator per-thread cache front go/no-go micro-bench: per-op cost of
 * three freelist front-end strategies on a single EL-pinned thread, plus a
 * decomposition of why the `scopeLocal` path is heavy on macosArm64.
 *
 * **Why.** A proposed allocator change adds a per-thread cache
 * front to recover the HTTPS Native regression. A first-principles read shows
 * the recovery mechanism is weak on EL-pinned engines: HTTPS hot buffers (read
 * 8 KiB / TLS plaintext 16 KiB / ciphertext ~17 KiB) are all ≤
 * `MAX_CACHED_CAPACITY` (32 KiB), so they already hit the per-EL [Freelist] on
 * the same EL thread and never reach `ChunkArena.carve`. A per-thread cache
 * front adds **zero** extra carve-bypass; its only effect is replacing the
 * freelist's thread-safe synchronisation (the Native default
 * `SpinLockFreelist`'s CAS acquire/release) with a same-thread plain LIFO.
 *
 * Front strategies (A/B/C):
 * - **A** `SpinLockFreelist`-equivalent push/pop — the current EL-pinned hot
 *   path (re-implemented; the production class is `private`).
 * - **B** plain `ArrayDeque` push/pop — the per-EL-child *plain field* path.
 * - **C** `scopeLocal { ArrayDeque }.current()` + push/pop — the off-EL path,
 *   which pays a `ThreadLocalScopeLocal.current()` per op.
 *
 * Decomposition of C's `current()` cost (D/E/F/G), because the first run showed
 * C−B ≈ 19 ns on M1 vs ≈ 5 ns on the x86_64 host — far above the
 * `ThreadLocalScopeLocal` KDoc's HashMap ~6.7 ns (an x86_64 figure). `current()` is
 * `perThreadStore.getOrPut(key)` where `perThreadStore` is a `@ThreadLocal
 * HashMap`:
 * - **D** `ThreadLocalScopeLocal` constructed directly, bypassing [scopeLocal] —
 *   see the 2026-07-01 correction below for what C−D actually isolates.
 * - **E** `@ThreadLocal HashMap.getOrPut(key)` inline — the `current()` body
 *   (TLS + HashMap), no virtual dispatch.
 * - **F** `@ThreadLocal Int` increment — TLS resolution cost alone.
 * - **G** plain (non-`@ThreadLocal`) `HashMap.getOrPut(key)` — HashMap cost
 *   alone. E−G isolates the TLS surcharge on the map; E−F isolates the map cost
 *   inside TLS.
 * - **H** plain LIFO behind a `pthread_self()` owner-thread guard — the
 *   cross-thread safety check a plain-field cache would need (it is only safe to
 *   touch from the owning EL thread; a cross-thread release must skip it).
 *
 * **Verdict (2026-06-24): the per-thread cache front is no-go.** The guard
 * is net-negative — H ≈ 30 ns > A ≈ 26 ns on M1 (`pthread_self` + `pthread_equal`
 * ≈ 17 ns), so a plain-field cache cannot cheaply guard cross-thread release. The
 * concrete-`ThreadLocalScopeLocal` alternative is race-free but dead-ends a
 * cross-thread-released buffer in the freeing thread's slot (leak). Cross-thread
 * free needs a sharded central allocator with an MPSC return queue (the next
 * allocator design step). This bench + its decomposition is the decision record;
 * kept `@Ignore`. **This verdict is unaffected by the correction below** — it
 * rests on the race / leak / net-negative-guard findings, not on C−D.
 *
 * **Correction (2026-07-01).** The original write-up attributed C−D (≈8.5 ns on
 * M1) to "Kotlin/Native arm64 interface virtual dispatch". That is wrong:
 * **D bypasses [scopeLocal] entirely** and constructs [ThreadLocalScopeLocal]
 * directly, so on macosArm64 — where [scopeLocal]'s Apple actual is a
 * `DispatchQueueLocal`-composite that calls `dispatch_get_specific` — C and D
 * exercise **different mechanisms**, not the same mechanism through an
 * interface vs. a concrete reference. C−D therefore measures "Apple's
 * GCD-composite path minus Linux/native's plain-HashMap path", not interface
 * dispatch in isolation. A controlled experiment that changed only the
 * `ScopeLocal` interface itself to a KMP `expect class` (identical composite
 * mechanism on both sides) found the M1 cost **unchanged**: this bench's C−D
 * 8.50 ns → 7.45 ns (noise-level), and a second, independent bench holding
 * `compositeSlot.current()` constant showed 17.9 ns → 18.3 ns (also
 * noise-level). Interface dispatch is not the dominant cost here; the real gap
 * between D and the Apple composite is most likely the `dispatch_get_specific`
 * GCD syscall itself (see the `dispatch_get_specific miss` breakdown in the
 * sibling `ScopeLocalCostBench`), which switching away from an interface
 * cannot remove. Investigating whether that GCD-syscall cost is itself
 * reducible is a separate, correctly-scoped follow-up.
 */
// Re-run: remove @Ignore, then
//   ./gradlew :keel-io:macosArm64Test --tests "*PerThreadCacheFrontMicroBenchmark"
//   ./gradlew :keel-io:linuxX64Test   --tests "*PerThreadCacheFrontMicroBenchmark"
@Ignore
class PerThreadCacheFrontMicroBenchmark {

    @Test
    @Suppress("IoBufLeak") // single buffer reused for push/pop roundtrips, released at the end
    fun compareFrontEnds() {
        val buf: IoBuf = NativeIoBuf(CLASS_SIZE)
        println("=== per-thread cache front per-op cost (Native, $CLASS_SIZE-byte class, single EL-pinned thread) ===")
        println("variant|ns/op")
        println("A spin-lock-freelist|${fmt(spinLockTrial(buf))}")
        println("B plain-lifo|${fmt(plainLifoTrial(buf))}")
        println("C scopelocal+lifo|${fmt(scopeLocalTrial(buf))}")
        println("D tlscope-concrete+lifo|${fmt(concreteScopeTrial(buf))}")
        println("E tls-hashmap-getorput|${fmt(tlsHashMapTrial())}")
        println("F tls-int-incr|${fmt(tlsIntTrial())}")
        println("G plain-hashmap-getorput|${fmt(plainHashMapTrial())}")
        println("H plain-lifo+pthread-self-guard|${fmt(guardedLifoTrial(buf))}")
        println("blackhole=${blackhole.value}")
        buf.release()
    }

    /** A: spin-lock freelist (mirrors the production private `SpinLockFreelist`). */
    private fun spinLockTrial(buf: IoBuf): Double {
        val fl = MicroSpinLockFreelist(CAP)
        return measure {
            fl.push(buf)
            if (fl.pop() != null) blackhole.value++
        }
    }

    /** B: plain LIFO (per-EL-child plain field, no synchronisation). */
    private fun plainLifoTrial(buf: IoBuf): Double {
        val list = ArrayDeque<IoBuf>(CAP)
        return measure {
            list.addLast(buf)
            if (list.removeLastOrNull() != null) blackhole.value++
        }
    }

    /**
     * H: plain LIFO behind a `pthread_self()` owner-thread guard — the
     * cross-thread safety check. The per-thread cache (plain field) is only safe
     * to touch from the owning EL thread; a cross-thread release must skip it and
     * fall to the thread-safe freelist. This measures the per-op cost of the guard
     * (pthread_self + pthread_equal) added to the plain-LIFO push/pop.
     */
    private fun guardedLifoTrial(buf: IoBuf): Double {
        val list = ArrayDeque<IoBuf>(CAP)
        val owner = pthread_self()
        return measure {
            if (pthread_equal(pthread_self(), owner) != 0) {
                list.addLast(buf)
                if (list.removeLastOrNull() != null) blackhole.value++
            }
        }
    }

    /** C: scopeLocal-resolved LIFO through the [ScopeLocal] interface (off-EL path). */
    private fun scopeLocalTrial(buf: IoBuf): Double {
        val slot: ScopeLocal<ArrayDeque<IoBuf>> = scopeLocal { ArrayDeque(CAP) }
        return measure {
            val list = slot.current()
            list.addLast(buf)
            if (list.removeLastOrNull() != null) blackhole.value++
        }
    }

    /** D: same as C but through the concrete [ThreadLocalScopeLocal] (no interface dispatch). */
    private fun concreteScopeTrial(buf: IoBuf): Double {
        val slot = ThreadLocalScopeLocal { ArrayDeque<IoBuf>(CAP) }
        return measure {
            val list = slot.current()
            list.addLast(buf)
            if (list.removeLastOrNull() != null) blackhole.value++
        }
    }

    /** E: the `current()` body — a `@ThreadLocal HashMap.getOrPut(key)`, no dispatch, no LIFO. */
    private fun tlsHashMapTrial(): Double = measure {
        @Suppress("UNCHECKED_CAST")
        val v = tlsStore.getOrPut(mapKey) { SENTINEL } as Int
        blackhole.value += v
    }

    /** F: TLS resolution alone — a `@ThreadLocal Int` increment. */
    private fun tlsIntTrial(): Double = measure {
        tlsCounter++
        blackhole.value += tlsCounter and 1
    }

    /** G: HashMap cost alone — a plain (non-`@ThreadLocal`) `HashMap.getOrPut(key)`. */
    private fun plainHashMapTrial(): Double {
        val map = HashMap<Any, Any>()
        return measure {
            @Suppress("UNCHECKED_CAST")
            val v = map.getOrPut(mapKey) { SENTINEL } as Int
            blackhole.value += v
        }
    }

    private inline fun measure(op: () -> Unit): Double {
        var w = 0
        while (w < WARMUP_ITERS) {
            op()
            w++
        }
        val samples = DoubleArray(SAMPLES)
        for (t in 0 until SAMPLES) {
            val mark = TimeSource.Monotonic.markNow()
            var i = 0
            while (i < TRIAL_ITERS) {
                op()
                i++
            }
            samples[t] = mark.elapsedNow().inWholeNanoseconds.toDouble() / TRIAL_ITERS
        }
        samples.sort()
        return samples[SAMPLES / 2]
    }

    /**
     * Copy of the production [Freelist] spin lock (which is `private` in
     * `SlabAllocator.kt`), kept here so the bench measures the same CAS
     * acquire/release shape without exposing the production class.
     */
    private class MicroSpinLockFreelist(private val maxSlots: Int) {
        private val list = ArrayDeque<IoBuf>(maxSlots)
        private val lock = AtomicReference(false)

        private inline fun <T> withSpinLock(block: () -> T): T {
            while (!lock.compareAndSet(false, true)) { /* spin */ }
            try {
                return block()
            } finally {
                lock.value = false
            }
        }

        fun push(buf: IoBuf): Boolean = withSpinLock {
            if (list.size < maxSlots) {
                list.addLast(buf)
                true
            } else {
                false
            }
        }

        fun pop(): IoBuf? = withSpinLock { if (list.isEmpty()) null else list.removeLast() }
    }

    private companion object {
        // AtomicInt defeats dead-code elimination of the pop / map result on Native.
        val blackhole = AtomicInt(0)

        /** Stable key reused across getOrPut calls (matches ThreadLocalScopeLocal's per-instance key). */
        val mapKey = Any()
        const val SENTINEL = 1

        /** 8 KiB = the page-tier read-buffer class, the dominant HTTPS hot class. */
        const val CLASS_SIZE = 8192
        const val CAP = 64
        const val WARMUP_ITERS = 200_000
        const val TRIAL_ITERS = 2_000_000
        const val SAMPLES = 5

        fun fmt(v: Double): String = (kotlin.math.round(v * 100.0) / 100.0).toString()
    }
}

// Top-level @ThreadLocal state for the decomposition variants E and F, mirroring
// how ThreadLocalScopeLocal declares its single @ThreadLocal store at top level.
@ThreadLocal
private val tlsStore: HashMap<Any, Any> = HashMap()

@ThreadLocal
private var tlsCounter: Int = 0
