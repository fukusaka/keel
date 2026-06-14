package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * Lifecycle-contract tests for [PooledAllocator] (Native [SlabAllocator] /
 * JVM [PooledDirectAllocator] — the shared base class). The contract:
 *
 * - `allocate` / `createChild` after `close` throw `IllegalStateException`.
 * - `close` is idempotent (a second call is a no-op).
 * - `close` drains the pool: every previously-pooled buffer's backing is
 *   freed and the pool becomes empty.
 * - `close` propagates to children: a parent's `close` closes every per-EL
 *   child produced by `createChild`.
 * - A buffer that was in use at `close` time is not touched; its later
 *   `release` runs the closed-flag branch in `returnToPool` and frees the
 *   backing directly instead of pushing into the drained pool.
 *
 * JS falls back to [DefaultAllocator] (no pool, no resources to release),
 * so the pool-specific assertions guard with [isPoolAllocator].
 */
class PooledAllocatorLifecycleTest {

    @Test
    fun `allocate after close throws ISE`() {
        val allocator = createPoolAllocator()
        if (!isPoolAllocator()) {
            // DefaultAllocator's close is a no-op and allocate stays open
            // by design (pool-less, nothing to invalidate).
            allocator.close()
            allocator.allocate(8192).release()
            return
        }
        allocator.close()
        assertFailsWith<IllegalStateException> { allocator.allocate(8192) }
    }

    @Test
    fun `createChild after close throws ISE`() {
        val allocator = createPoolAllocator()
        if (!isPoolAllocator()) {
            allocator.close()
            // DefaultAllocator's createChild returns `this`; both
            // pre- and post-close it is the same object, no ISE expected.
            assertEquals(allocator, allocator.createChild())
            return
        }
        allocator.close()
        assertFailsWith<IllegalStateException> { allocator.createChild() }
    }

    @Test
    fun `close is idempotent`() {
        val allocator = createPoolAllocator()
        allocator.close()
        // A second close must not throw; if any subclass forgets the
        // closed-flag guard the second pass would `pthread_mutex_destroy`
        // an already-destroyed mutex (Native MutexFreelist) or double-free
        // the chunk arena.
        allocator.close()
    }

    @Test
    fun `close drains the pool`() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator() as PooledAllocator
        // Fill one slot, return it to the pool, then close.
        val buf = allocator.allocate(8192)
        buf.release()
        assertEquals(1, allocator.cachedCountOf(8192), "buffer should sit in the pool")

        allocator.close()

        assertEquals(
            0,
            allocator.cachedCountOf(8192),
            "close must drain the pool and freeBacking every pooled buffer",
        )
    }

    @Test
    fun `close propagates to children`() {
        if (!isPoolAllocator()) return
        val parent = createPoolAllocator()
        val child = parent.createChild()
        assertNotSame(parent, child, "per-EL child must be a fresh instance")

        parent.close()

        // The child is closed via the propagation path. Allocate must now
        // throw on the child too.
        assertFailsWith<IllegalStateException> { child.allocate(8192) }
    }

    @Test
    fun `release after close goes through freeBacking instead of the pool`() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator() as PooledAllocator
        // Allocate a buffer and DO NOT release it before closing — it is
        // in use at close time and stays alive on its refCount.
        val buf = allocator.allocate(8192)

        allocator.close()

        // The pool was drained by close; the buffer's release now runs the
        // closed-flag branch in returnToPool and frees its backing
        // directly. Nothing is pushed into the (drained) pool.
        buf.release()
        assertEquals(
            0,
            allocator.cachedCountOf(8192),
            "post-close release must NOT push back into the drained pool",
        )
    }

    @Test
    fun `large allocations from before close are released gracefully`() {
        if (!isPoolAllocator()) return
        val allocator = createPoolAllocator()
        // 1 MiB is above MAX_CACHED_CAPACITY (32 KiB), so this is an
        // exact, unpooled allocation; release uses freeBacking and should
        // not be affected by the closed-flag branch beyond the early
        // freeBacking direct call.
        val huge = allocator.allocate(1024 * 1024)
        assertTrue(huge.capacity >= 1024 * 1024)

        allocator.close()
        huge.release()
    }
}
