package io.github.fukusaka.keel.server.ktor.cio

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide serialisation of ktor-http-cio parser calls for Linux targets.
 *
 * keel's Linux engines (epoll, io_uring) use a boss/worker EventLoop model
 * with `availableProcessors()` worker threads — the same multi-threaded design
 * as the macOS kqueue engine. Multiple worker threads can call
 * [parseRequest][io.ktor.http.cio.parseRequest] simultaneously, which causes
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
 * **Performance**: on a 32-core host with the default `availableProcessors()`
 * worker count, the mutex bounds serialisation overhead to O(numWorkers ×
 * parseTime) ≈ 32 × 40 µs ≈ 1.3 ms per request at peak concurrency —
 * acceptable given the Ktor CIO adapter is not the high-throughput path
 * (use `pipeline-http-epoll` / `pipeline-http-io-uring` for maximum throughput).
 *
 * Within a single worker, coroutines are cooperatively scheduled (one runs
 * at a time), so the mutex is only ever contended across workers — not within
 * a single worker's connection set.
 *
 * **Upstream**: tracked at the ktor issue tracker. When `HeadersDataPool` is
 * reworked to release the pool lock around `clearInstance`, this class can
 * become a no-op on all platforms and eventually be deleted.
 */
private val sharedMutex = Mutex()

internal actual class HeaderParseMutex actual constructor() {
    actual suspend fun <T> withLock(block: suspend () -> T): T = sharedMutex.withLock { block() }
}
