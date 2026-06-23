package io.github.fukusaka.keel.buf

/**
 * A platform mutex guarding shared-central allocator state — the [ChunkArena]
 * and its [PooledChunk]s — against concurrent allocate / release from threads
 * that are not pinned to a single EventLoop.
 *
 * **Why a dedicated lock, separate from [Freelist]**: the per-size-class
 * [Freelist] guards the cache (recycled-buffer) layer; this lock guards the
 * chunk back-end (carve / reclaim), which a *shared* central arena exposes to
 * off-EventLoop callers (Coroutine-mode `Channel.allocator` users, the
 * NWConnection GCD worker pool). An EL-pinned allocator whose arena is never
 * shared still pays the uncontended lock fast-path on every pool miss; the
 * shared arena pays the contended path only when two EventLoops miss at once.
 *
 * **Platform mapping**:
 * - Native: a `pthread_mutex_t` allocated from `nativeHeap`, released by [close]
 *   (`pthread_mutex_destroy`). Mirrors [MutexFreelist]'s native lifecycle.
 * - JVM: a `java.util.concurrent.locks.ReentrantLock` (GC-managed, [close] is a no-op).
 * - JS: a no-op — Kotlin/JS runs on a single-threaded event loop and the pooling
 *   allocators are not used there (`DefaultAllocator` has no chunk arena), so the
 *   lock has no work to do.
 *
 * **Thread safety**: [lock] / [unlock] are the standard mutual-exclusion contract.
 * [close] must run only after the arena is quiescent (no thread mid-lock), the same
 * teardown discipline [PooledAllocator.close] already guarantees for [Freelist.close].
 */
internal expect class ArenaLock() {
    /** Acquires the lock, blocking until available. */
    fun lock()

    /** Releases the lock. Must be paired with a preceding [lock] on the same thread. */
    fun unlock()

    /**
     * Releases any OS resource the lock holds (`pthread_mutex_t`). Idempotent —
     * a second call is a no-op. After [close] the lock must not be used.
     */
    fun close()
}

/**
 * Runs [block] while holding [this] lock, releasing it on every exit path
 * (normal return or exception). The unlock runs in `finally` so an exception
 * thrown by [block] does not leak the lock.
 */
internal inline fun <T> ArenaLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
