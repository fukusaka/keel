package io.github.fukusaka.keel.buf

/**
 * JS [ArenaLock] — a no-op. Kotlin/JS runs on a single-threaded event loop, and
 * the pooling allocators that own a [ChunkArena] are not used on JS
 * (`DefaultAllocator` has no chunk arena), so there is no concurrent access to
 * guard. The actual exists only to satisfy the `expect` on common code paths.
 */
internal actual class ArenaLock actual constructor() {
    actual fun lock() {
        // Single-threaded JS event loop: no mutual exclusion needed.
    }

    actual fun unlock() {
        // No-op — see lock().
    }

    actual fun close() {
        // No resource to release.
    }
}
