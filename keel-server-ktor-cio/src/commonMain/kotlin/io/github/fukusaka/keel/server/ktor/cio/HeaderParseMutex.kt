package io.github.fukusaka.keel.server.ktor.cio

/**
 * Serialises both ends of ktor-http-cio's `HeadersDataPool` borrow ↔
 * recycle cycle (`parseRequest` and the matching `request.release()`) so
 * concurrent pool access does not collapse throughput on Kotlin/Native.
 *
 * **Why this exists**: On Kotlin/Native with multi-worker engines (e.g.
 * kqueue, which spawns one EventLoop thread per CPU core), multiple threads
 * can interact with `HeadersDataPool` simultaneously. ktor-io's
 * `DefaultPool.posix.kt` runs the subclass `clearInstance` hook **inside**
 * `synchronized(lock)` — an anti-pattern that holds the pool lock across an
 * arbitrary subclass extension point. ktor-http-cio's `HeadersDataPool`
 * exploits this anti-pattern: its `clearInstance` calls `instance.release()`
 * which calls `IntArrayPool.recycle()` for each header-array, taking a
 * second pool's lock while still holding the first.
 *
 * Under multi-worker concurrency, ktor-io's `SynchronizedObject` (an
 * `AtomicReference<LockState>` spin lock that escalates to a pooled
 * `pthread_mutex` on contention) does not enjoy the JVM's biased-locking
 * / JIT-elision optimisations. Once several workers contend, futex_wait
 * pile-up amplifies the cascading nested-lock wait and parser throughput
 * collapses to ≈ 0 RPS.
 *
 * Both `parseRequest` (borrow) **and** `request.release()` (recycle) must
 * serialise through this mutex — serialising only the borrow side leaves
 * an open race where worker A's release runs concurrently with worker B's
 * borrow. Empirical measurement on Linux x86_64 / epoll showed 4/10
 * 30-second-collapse iterations with parseRequest-only serialisation,
 * dropping to 0/10 once `request.release()` was wrapped too.
 *
 * On the JVM `synchronized` is reentrant + biased-locking + JIT-elided, so
 * concurrent access is safe.  JVM uses a no-op pass-through.
 *
 * **Platform policy**:
 * - **JVM**: no-op pass-through (see `HeaderParseMutex.jvm.kt`)
 * - **Native** (kqueue, NWConnection, epoll, io_uring): process-wide
 *   [kotlinx.coroutines.sync.Mutex] (see `HeaderParseMutex.native.kt`).
 *   All Native engines use the same boss/worker model with
 *   `availableProcessors()` worker threads; the mutex prevents concurrent
 *   pool access across workers.
 *
 * Empirical signatures across the documented symptom (Kotlin/Native
 * multi-worker, fresh server taking ≈ 50 concurrent connections at once):
 *
 * | Configuration                                  | flaky runs | median RPS |
 * | ---                                            | ---        | ---        |
 * | no serialisation (historical baseline)         | 6 / 20     | ≈ 14 500   |
 * | parseRequest only serialised (pre-#502)        | 4 / 10     | ≈ 27 000   |
 * | parseRequest + request.release() serialised    | 0 / 10     | ≈ 25 000   |
 *
 * Historical baseline: macOS M1 kqueue, wrk 4t/100c/10s, 20 iterations —
 * the original motivation for introducing this mutex. pre-#502 / post-#502
 * rows: Linux x86_64 epoll, k6 50 VU `compression-upload` 15 s, 10
 * server-restart iterations — the case that motivated PR #502 extending
 * the coverage from borrow-only to borrow + recycle.
 *
 * **Upstream**: when ktor-io's `DefaultPool` releases the pool lock around
 * `clearInstance` (or `HeadersDataPool` stops nesting another pool's
 * recycle inside its own clearInstance), this class can become a no-op on
 * all platforms.
 */
internal expect class HeaderParseMutex() {
    /** Runs [block] under the platform-specific serialisation policy. */
    suspend fun <T> withLock(block: suspend () -> T): T
}
