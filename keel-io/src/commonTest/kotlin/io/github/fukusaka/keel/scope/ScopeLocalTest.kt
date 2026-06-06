package io.github.fukusaka.keel.scope

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Cross-platform contract tests for [ScopeLocal] / [scopeLocal] on the calling
 * scope: `fallback` is resolved lazily and cached (invoked at most once per
 * scope), the cached value is consistent across reads and reflects mutations,
 * and independent instances do not share state.
 *
 * Per-thread / per-queue isolation across scopes is exercised by the native
 * multi-pthread test (`ScopeLocalNativeIsolationTest`) and
 * `DispatchQueueLocalTest`.
 */
class ScopeLocalTest {

    private class Box(var n: Int)

    @Test
    fun `current resolves fallback lazily and caches it`() {
        var calls = 0
        val local = scopeLocal { Box(calls++) }
        val first = local.current()
        val second = local.current()
        assertSame(first, second)
        assertEquals(0, first.n)
        assertEquals(1, calls, "fallback must be invoked at most once per scope")
    }

    @Test
    fun `independent instances do not share state`() {
        val a = scopeLocal { Box(1) }
        val b = scopeLocal { Box(2) }
        assertNotSame(a.current(), b.current())
        assertEquals(1, a.current().n)
        assertEquals(2, b.current().n)
    }

    @Test
    fun `mutations through the resolved value are visible on the next read`() {
        val local = scopeLocal { Box(0) }
        local.current().n = 42
        assertEquals(42, local.current().n)
    }
}
