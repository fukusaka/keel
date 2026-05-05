package io.github.fukusaka.keel.server.ktor.cio

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide serialisation of ktor-http-cio parser calls for Kotlin/Native targets.
 *
 * All keel Native engines (kqueue, NWConnection, epoll, io_uring) use a boss/worker
 * EventLoop model with `availableProcessors()` worker threads. Multiple workers can
 * call [parseRequest][io.ktor.http.cio.parseRequest] simultaneously, which causes
 * a lock contention storm inside ktor's process-wide `HeadersDataPool`:
 * `borrow()` holds the pool's internal lock while calling `clearInstance`,
 * which re-enters the same lock via `recycle()`. On Kotlin/Native the lock
 * is a non-reentrant primitive, so concurrent callers collapse to ≈ 0 RPS.
 *
 * A single process-wide [Mutex] prevents more than one worker from entering
 * the parser at a time. The mutex is held only for the duration of the
 * synchronous header-parse call (not body decoding), so suspension does not
 * block the I/O thread — other connections continue their I/O work while
 * one parses headers.
 *
 * Within a single worker, coroutines are cooperatively scheduled (one runs
 * at a time), so the mutex is only ever contended across workers — not within
 * a single worker's connection set.
 *
 * Empirically (macOS M1, kqueue default workers ≈ 8 cores,
 * wrk 4t/100c/10s, 20 iterations):
 *
 * | Configuration                                | failures (0 RPS) | median RPS | p99      |
 * | ---                                          | ---              | ---        | ---      |
 * | parallel parsers (no serialisation)          | 6 / 20           | ≈ 14 500   | ≈ 11 ms  |
 * | single worker (`threads=1`)                  | 0 / 20           | ≈ 36 000   | ≈ 5.3 ms |
 * | parallel I/O + serialised parser (this class)| 0 / 20           | ≈ 43 400   | ≈ 2.8 ms |
 *
 * **Upstream**: tracked at the ktor issue tracker. When `HeadersDataPool` is
 * reworked to release the pool lock around `clearInstance`, this class can
 * become a no-op on all platforms and eventually be deleted.
 */
private val sharedMutex = Mutex()

internal actual class HeaderParseMutex actual constructor() {
    actual suspend fun <T> withLock(block: suspend () -> T): T = sharedMutex.withLock { block() }
}
