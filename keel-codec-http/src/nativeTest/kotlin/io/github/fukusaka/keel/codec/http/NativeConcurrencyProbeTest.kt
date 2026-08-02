package io.github.fukusaka.keel.codec.http

import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import platform.posix.pthread_create
import platform.posix.pthread_join
import platform.posix.pthread_tVar
import kotlin.native.concurrent.ThreadLocal
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins down Kotlin/Native concurrency semantics behind the
 * [HttpHeadersPool] thread-safety fix, using the **exact thread
 * mechanism keel's Native EventLoops use** — raw `pthread_create` +
 * `staticCFunction` (see `EpollEventLoop.start`), not Kotlin `Worker`.
 *
 * Two facts the fix relies on:
 *
 * 1. **A plain top-level `object`'s mutable state is SHARED across
 *    threads** — so the original global pool (a single shared
 *    `ArrayDeque`) genuinely races on Native, exactly as it does on
 *    the JVM. (Native real-network runs did not crash like the JVM
 *    because the Native data race is silent UB, not a bounds-checked
 *    exception — arguably worse, since it can corrupt headers
 *    invisibly.)
 * 2. **`@ThreadLocal` gives per-OS-thread isolation on pthreads** — so
 *    the per-thread pool stack (`HttpHeadersPool` → `headersPoolStack`,
 *    `@ThreadLocal` on Native) is correct on the engine's pthread
 *    EventLoop threads.
 *
 * Each test spawns pthreads with the engine's idiom: Arena-alloc'd
 * `pthread_tVar`, a `StableRef` argument, joined via `threadPtr.ptr[0]`.
 */
class NativeConcurrencyProbeTest {

    // A plain shared object — mirrors the original global HttpHeadersPool.
    object SharedState {
        var value: Int = 0
    }

    // A @ThreadLocal object — the mechanism behind `headersPoolStack`.
    @ThreadLocal
    object IsolatedState {
        var value: Int = 0
    }

    private class IntSlot(val results: IntArray, val index: Int)

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun `plain object state is shared across pthreads`() {
        // A pthread writes 42; after join the main thread must observe
        // it -> proves plain object state is shared (the global-pool bug
        // is real on Native).
        SharedState.value = 0
        val arena = Arena()
        try {
            val threadPtr = arena.alloc<pthread_tVar>()
            val ref = StableRef.create(Unit)
            val rc = pthread_create(
                threadPtr.ptr,
                null,
                staticCFunction { arg ->
                    SharedState.value = 42
                    arg!!.asStableRef<Unit>().dispose()
                    null
                },
                ref.asCPointer(),
            )
            check(rc == 0) { "pthread_create failed: rc=$rc" }
            pthread_join(threadPtr.ptr[0], null)
        } finally {
            arena.clear()
        }
        println("=== Native plain-object sharing probe (pthread) ===")
        println("  pthread wrote SharedState.value=42")
        println("  main observes SharedState.value=${SharedState.value}")
        assertTrue(
            SharedState.value == 42,
            "expected shared state (42); got ${SharedState.value} — if isolated, the " +
                "global-pool bug would not exist on Native, which contradicts the crash analysis",
        )
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun `ThreadLocal isolation on pthread-created threads`() {
        IsolatedState.value = 99 // main thread's copy
        val n = 4
        val results = IntArray(n) { -1 }
        // Sequential create/join still proves per-OS-thread isolation:
        // each pthread independently observes its own default 0, not
        // main's 99, and main is never affected by a pthread's write.
        for (i in 0 until n) {
            val arena = Arena()
            try {
                val threadPtr = arena.alloc<pthread_tVar>()
                val ref = StableRef.create(IntSlot(results, i))
                val rc = pthread_create(
                    threadPtr.ptr,
                    null,
                    staticCFunction { arg ->
                        val slot = arg!!.asStableRef<IntSlot>().get()
                        val before = IsolatedState.value // own copy, expect 0
                        IsolatedState.value = 7 + slot.index
                        slot.results[slot.index] = before
                        arg.asStableRef<IntSlot>().dispose()
                        null
                    },
                    ref.asCPointer(),
                )
                check(rc == 0) { "pthread_create failed: rc=$rc" }
                pthread_join(threadPtr.ptr[0], null)
            } finally {
                arena.clear()
            }
        }
        println("=== Native @ThreadLocal isolation probe (pthread) ===")
        println("  main set IsolatedState.value=99")
        println("  pthreads observed (own copy) before-write: ${results.toList()}")
        println("  main still sees=${IsolatedState.value}")
        results.forEachIndexed { i, v ->
            assertTrue(v == 0, "pthread #$i leaked a value (expected own default 0): $v")
        }
        assertTrue(IsolatedState.value == 99, "main saw a pthread's @ThreadLocal write: ${IsolatedState.value}")
    }
}
