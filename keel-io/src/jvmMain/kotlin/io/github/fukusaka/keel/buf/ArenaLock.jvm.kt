package io.github.fukusaka.keel.buf

import java.util.concurrent.locks.ReentrantLock

/**
 * JVM [ArenaLock] backed by a `java.util.concurrent.locks.ReentrantLock`.
 *
 * The lock is a plain Java object with no native resource, so it is reclaimed by
 * the GC together with the arena it guards; [close] is a no-op (matching
 * [MutexFreelist]'s JVM lifecycle).
 */
internal actual class ArenaLock actual constructor() {
    private val delegate = ReentrantLock()

    actual fun lock() = delegate.lock()

    actual fun unlock() = delegate.unlock()

    actual fun close() {
        // No native resource: the ReentrantLock is GC-managed.
    }
}
