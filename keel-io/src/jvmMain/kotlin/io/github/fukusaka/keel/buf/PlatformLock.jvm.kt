package io.github.fukusaka.keel.buf

import java.util.concurrent.locks.ReentrantLock

internal actual class PlatformLock actual constructor() {
    private val delegate = ReentrantLock()

    actual fun lock() = delegate.lock()
    actual fun unlock() = delegate.unlock()
    actual fun close() = Unit // GC-managed; no resource to release.
}
