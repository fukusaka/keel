package io.github.fukusaka.keel.server.ktor.cio

/**
 * Serialises calls to ktor-http-cio's `parseRequest` so concurrent
 * header-parse calls do not contend on ktor's shared `HeadersDataPool`.
 *
 * **Why this exists**: On Kotlin/Native with multi-worker engines (e.g.
 * kqueue, which spawns one EventLoop thread per CPU core), multiple threads
 * can call `parseRequest` simultaneously.  ktor-http-cio's `parseHeaders`
 * calls `HeadersDataPool.borrow()`, which acquires an internal lock and,
 * while holding it, invokes `clearInstance(item)`.  The `HeadersData.release()`
 * path called from `clearInstance` re-enters `HeadersDataPool.recycle()`
 * against the same lock — a pathological interaction under concurrent
 * multi-thread access that collapses throughput to ≈ 0 RPS.
 *
 * On the JVM `synchronized` is reentrant and JIT-optimised, so concurrent
 * access is safe.  JVM uses a no-op pass-through.
 *
 * **Platform policy**:
 * - **JVM**: no-op pass-through (see `HeaderParseMutex.jvm.kt`)
 * - **Linux** (epoll, io_uring): process-wide [kotlinx.coroutines.sync.Mutex]
 *   (see `HeaderParseMutex.linux.kt`).  epoll and io_uring engines use the same
 *   boss/worker model as kqueue: `availableProcessors()` worker threads, each
 *   on its own pthread.  The same concurrent pool-access risk applies.
 * - **Apple** (kqueue, NWConnection): process-wide [kotlinx.coroutines.sync.Mutex]
 *   (see `HeaderParseMutex.apple.kt`).  kqueue spawns one EventLoop worker
 *   per CPU core; the mutex prevents concurrent pool access across workers.
 *
 * Empirically (macOS M1, kqueue ≈ 8 workers, wrk 4t/100c/10s):
 *
 * | Configuration                                | failures (0 RPS) | median RPS | p99      |
 * | ---                                          | ---              | ---        | ---      |
 * | parallel parsers (no serialisation)          | 6 / 20           | ≈ 14 500   | ≈ 11 ms  |
 * | single worker (`threads=1`)                  | 0 / 20           | ≈ 36 000   | ≈ 5.3 ms |
 * | parallel I/O + serialised parser (Apple impl)| 0 / 20           | ≈ 43 400   | ≈ 2.8 ms |
 *
 * **Upstream**: when `HeadersDataPool` is reworked to release its lock around
 * `clearInstance`, this class can become a no-op on all platforms.
 */
internal expect class HeaderParseMutex() {
    /** Runs [block] under the platform-specific serialisation policy. */
    suspend fun <T> withLock(block: suspend () -> T): T
}
