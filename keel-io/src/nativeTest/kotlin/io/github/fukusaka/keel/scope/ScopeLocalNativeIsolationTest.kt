package io.github.fukusaka.keel.scope

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies that [scopeLocal] gives **per-OS-thread isolation** on the raw
 * pthreads keel's Native EventLoops use (spawned via `pthread_create` +
 * `staticCFunction`, as in `EpollEventLoop.start`).
 *
 * This is the regression guard for the Apple **composite** actual: an Apple
 * EventLoop is either NWConnection (GCD serial queue) **or** kqueue (raw
 * pthread). On a kqueue pthread there is no installed GCD queue, so
 * [ScopeLocal.current] takes the `DispatchQueueLocal` fallback path — which must
 * resolve to a per-pthread `@ThreadLocal` slot, not a shared or
 * fresh-per-call value. On Linux the same `scopeLocal` resolves to the
 * `@ThreadLocal` slot directly. Both must isolate per pthread.
 */
class ScopeLocalNativeIsolationTest {

    private class Box(var n: Int)

    private class Slot(
        val local: ScopeLocal<Box>,
        val before: IntArray,
        val index: Int,
    )

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun `scopeLocal isolates values per pthread on the fallback path`() {
        val local = scopeLocal { Box(0) }
        // Main thread mutates its own slot; worker pthreads must never see it.
        local.current().n = 99
        val n = 4
        val before = IntArray(n) { -1 }
        for (i in 0 until n) {
            val arena = Arena()
            try {
                val threadPtr = arena.alloc<pthread_tVar>()
                val ref = StableRef.create(Slot(local, before, i))
                val rc = pthread_create(
                    threadPtr.ptr,
                    null,
                    staticCFunction { arg ->
                        val slot = arg!!.asStableRef<Slot>().get()
                        // First read on this pthread: own fresh Box(0), not main's 99.
                        slot.before[slot.index] = slot.local.current().n
                        slot.local.current().n = 7 + slot.index
                        // Cached within this pthread: same instance, new value.
                        arg.asStableRef<Slot>().dispose()
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
        before.forEachIndexed { i, v ->
            assertTrue(v == 0, "pthread #$i observed a leaked value (expected own default 0): $v")
        }
        assertEquals(99, local.current().n, "main thread saw a worker pthread's write")
    }
}
