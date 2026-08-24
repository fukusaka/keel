@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.fukusaka.keel.buf

import kotlin.concurrent.atomics.AtomicInt

/**
 * A minimal compare-and-set test-and-set spin lock — the busy-wait analogue of
 * [ArenaLock] with **zero OS resources** (no `init` / `destroy` / leak surface),
 * for guarding a very short critical section under low contention.
 *
 * The CAS mechanism is the one the Native `SpinLockFreelist` uses; this lifts it
 * into commonMain so the per-size-class subpage pool heads ([ChunkArena]) can have
 * one lock each without paying ~30 `pthread_mutex` lifecycles per arena. The
 * critical section it guards is a handful of bitmap / linked-list writes, and the
 * EL-pinned engines access it uncontended (the acquire is then a single
 * uncontended CAS); JS is single-threaded so the CAS always succeeds at once.
 *
 * One use is wider than that: [PooledAllocator]'s child list holds this across
 * a whole child's construction, microseconds rather than instructions. That is
 * chosen for when it is taken — once per child, off every data path — and is
 * argued where it is taken rather than here.
 *
 * **Not reentrant** and **not fair**: a thread that already holds the lock and
 * calls [lock] again spins forever. Acquire / release must strictly pair on one
 * thread. There is no `close` — the [AtomicInt] is GC-managed on every platform.
 */
internal class SpinLock {
    private val state = AtomicInt(0)

    /** Spins until the lock is acquired. */
    fun lock() {
        while (!state.compareAndSet(expectedValue = 0, newValue = 1)) { /* spin */ }
    }

    /** Releases the lock. Must pair with a preceding [lock] on the same thread. */
    fun unlock() {
        state.store(0)
    }
}

/**
 * Runs [block] while holding [this] spin lock, releasing it on every exit path.
 * The unlock runs in `finally` so an exception thrown by [block] does not strand
 * the lock held.
 */
internal inline fun <T> SpinLock.withLock(block: () -> T): T {
    lock()
    try {
        return block()
    } finally {
        unlock()
    }
}
