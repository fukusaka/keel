package io.github.fukusaka.keel.buf

// JS engines run single-threaded (no shared-memory races without explicit
// worker bridges) so the lock is structurally unnecessary. Methods are
// intentionally no-ops; the expect/actual contract is preserved so PooledAllocator
// can construct an instance uniformly across all platforms without the call site
// branching on `expect class PooledAllocator?` (which would defeat KMP shared code).
internal actual class PlatformLock actual constructor() {
    actual fun lock() = Unit
    actual fun unlock() = Unit
    actual fun close() = Unit
}
