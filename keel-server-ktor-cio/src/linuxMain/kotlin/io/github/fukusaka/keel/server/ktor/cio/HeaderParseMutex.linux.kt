package io.github.fukusaka.keel.server.ktor.cio

/**
 * Linux no-op pass-through.
 *
 * keel's Linux engines (epoll, io_uring) run all connections on a single
 * EventLoop pthread. Only one coroutine executes at any given pthread-time
 * instant, so concurrent access to ktor's `HeadersDataPool` is impossible:
 * the pool's internal state is never touched by two callers simultaneously.
 *
 * Serialising `parseRequest` with a process-wide [kotlinx.coroutines.sync.Mutex]
 * (as the macOS implementation does for kqueue's multi-worker model) would
 * cause all connections to queue behind each other at the header-parse step.
 * With N connections that all arrive at `parseRequest` simultaneously, the
 * average wait grows as O(N × parseTime) — empirically measured as ~2865 µs
 * per request at 50 VUs vs ~40 µs actual parse time (K12).
 *
 * Without the mutex, connections on the same EventLoop can interleave freely
 * at coroutine suspension points inside `parseRequest`, each making
 * independent progress as their data arrives.
 */
internal actual class HeaderParseMutex actual constructor() {
    actual suspend fun <T> withLock(block: suspend () -> T): T = block()
}
