package io.github.fukusaka.keel.server.ktor.cio

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide serialisation of ktor-http-cio's `HeadersDataPool` access for
 * Kotlin/Native targets. Covers both ends of the borrow ↔ recycle cycle —
 * `parseRequest` (which borrows) and `request.release()` (which recycles).
 *
 * All keel Native engines (kqueue, NWConnection, epoll, io_uring) use a
 * boss/worker EventLoop model with `availableProcessors()` worker threads.
 * Multiple workers can interact with `HeadersDataPool` simultaneously, which
 * causes a lock contention storm at the level of ktor-io's
 * `DefaultPool.posix.kt` and ktor-http-cio's `HeadersDataPool`:
 *
 * 1. `DefaultPool.borrow()` runs the subclass `clearInstance` hook **inside**
 *    `synchronized(lock)` — anti-pattern that holds the pool lock across an
 *    arbitrary subclass extension point.
 * 2. `HeadersDataPool.clearInstance` does `instance.release()`, which calls
 *    `IntArrayPool.recycle(array)` for each header-array — taking a *second*
 *    pool's lock while still holding the first.
 * 3. ktor-io's `SynchronizedObject` is an `AtomicReference<LockState>` spin
 *    lock that escalates to a pooled `pthread_mutex` on contention. Once
 *    several workers contend, futex_wait pile-up amplifies the cascading
 *    nested-lock wait; on Kotlin/Native the synchronized primitive does not
 *    enjoy the JVM's biased-locking / JIT-elision optimisations, so the
 *    storm collapses parser throughput to a small fraction of the ideal.
 *
 * It is **not enough to serialise only the `borrow` side** — the `recycle`
 * runs at request end (out of band w.r.t. parseRequest) and contends with
 * the next worker's borrow exactly as much. Both ends must serialise to
 * close the race; serialising the borrow alone leaves a 30s-collapse
 * window under multi-worker bursts (e.g. fresh Native instance taking 50
 * keep-alive connections after a deployment) where one in three iterations
 * loses its k6 / load generator request to a pool stall.
 *
 * A single process-wide [Mutex] prevents more than one worker from entering
 * either path at a time. The mutex is held only for synchronous pool
 * operations (not body decoding), so suspension does not block the I/O
 * thread — other connections continue their I/O work while one parses or
 * recycles headers.
 *
 * Within a single worker, coroutines are cooperatively scheduled (one runs
 * at a time), so the mutex is only ever contended across workers — not within
 * a single worker's connection set.
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
 * **Upstream**: when ktor-io's `DefaultPool` releases its lock around
 * `clearInstance` (or `HeadersDataPool` stops nesting another pool's
 * recycle inside its own clearInstance), this class can become a no-op on
 * all platforms and eventually be deleted.
 */
private val sharedMutex = Mutex()

internal actual class HeaderParseMutex actual constructor() {
    actual suspend fun <T> withLock(block: suspend () -> T): T = sharedMutex.withLock { block() }
}
