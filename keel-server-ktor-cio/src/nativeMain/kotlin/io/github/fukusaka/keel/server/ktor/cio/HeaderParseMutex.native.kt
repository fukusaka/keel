package io.github.fukusaka.keel.server.ktor.cio

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide serialisation of ktor-http-cio parser calls.
 *
 * Why a single shared [Mutex] across every connection in the process:
 * ktor's `HeadersDataPool` is itself a process-wide singleton, and the
 * lock contention storm is on **that** pool, not on any per-handler
 * state.  Per-handler / per-connection serialisation would not help — we
 * need to serialise every parser caller in the JVM.
 *
 * The mutex is held only for the duration of a single `parseRequest` or
 * `parseHttpBody` invocation, so `withLock` does not block the I/O
 * thread (the suspension is coroutine-level, not pthread-level).
 */
private val sharedMutex = Mutex()

internal actual class HeaderParseMutex actual constructor() {
    actual suspend fun <T> withLock(block: suspend () -> T): T = sharedMutex.withLock { block() }
}
