package io.github.fukusaka.keel.native.posix

import kotlin.concurrent.AtomicInt

/**
 * The mutual exclusion the POSIX fakes use to hold their scripted state.
 *
 * ## Why the fakes need one at all
 *
 * A fake is written by the test thread (arming queues, setting defaults) and by
 * the EventLoop thread (every syscall the code under test issues dequeues a
 * response and bumps a counter), then read back by the test thread. Both fakes
 * hold that state in `ArrayDeque` / `MutableMap` / plain `Int`, none of which
 * survives concurrent use.
 *
 * The contract the fakes used to state — "single-threaded only; wrap in
 * `synchronized` at the call site" — cannot be honoured by anyone. The other
 * caller is production code: the engine calls `socket.read(...)` from its own
 * loop, and no test can wrap that. What actually held was narrower and
 * undocumented: a test dispatches a marker through the loop's FIFO and awaits
 * it, so the loop's writes are published before the test reads. Every seam test
 * that reads a counter does this today (audited 2026-08-15, 62 sites), but
 * nothing in the fake said so, and nothing failed when it was not done.
 *
 * ## Why a spin lock rather than a mutex
 *
 * Neither fake has a lifecycle. They are constructed inline —
 * `FakeNativeSocket().apply { … }` — in roughly thirty files, with no `close()`
 * and no fixture obliged to call one. A `pthread_mutex_t` would need allocating
 * and destroying, which means adding a teardown obligation to every one of those
 * sites; a sibling fake did exactly that and ten of nineteen files forgot it.
 *
 * The critical sections here are a map lookup and a deque removal, contended by
 * at most two threads, in test code. Spinning is the cheaper mistake.
 *
 * ## What this does not give you
 *
 * Atomicity per call, not per test. Two calls that a test wants to see as one
 * step are still two steps to a concurrent reader — the fake serialises its own
 * state, it does not order the code under test. Waiting for the loop before
 * reading remains the way to know *which* calls have happened; this only ensures
 * that what you read is not torn.
 */
internal class FakeSocketLock {

    // @PublishedApi so the inline [withLock] can reach it after expansion at the
    // call site, the same reason `NativeMutex` exposes its own handle that way.
    @PublishedApi
    internal val state: AtomicInt = AtomicInt(UNLOCKED)

    /**
     * Runs [block] holding the lock, releasing it on every exit path — including
     * a throw from [block], which is how a scripted one-shot leaves the guarded
     * region.
     *
     * Not reentrant: a guarded region must not call another. Both fakes are
     * written so the regions are leaves.
     */
    inline fun <T> withLock(block: () -> T): T {
        while (!state.compareAndSet(UNLOCKED, LOCKED)) {
            // Spin. See the class KDoc for why this is the right shape here.
        }
        try {
            return block()
        } finally {
            state.value = UNLOCKED
        }
    }

    @PublishedApi
    internal companion object {
        const val UNLOCKED: Int = 0
        const val LOCKED: Int = 1
    }
}
