package io.github.fukusaka.keel.scope

/**
 * Linux [ScopeLocal] actual: a thin wrapper over [ThreadLocalScopeLocal], the
 * Kotlin/Native `@ThreadLocal`-backed per-OS-thread slot. Linux has no GCD, so
 * each EventLoop pthread owns its slot directly — no composition with a
 * queue-scoped primitive is needed (contrast the Apple actual). [fallback]
 * supplies each thread's value on first read.
 */
actual class ScopeLocal<T : Any> internal constructor(fallback: () -> T) {
    private val impl = ThreadLocalScopeLocal(fallback)

    actual fun current(): T = impl.current()

    actual fun isScopedHere(): Boolean = impl.isScopedHere()
}

/** Linux [scopeLocal] actual: constructs the [ScopeLocal] wrapper above. */
actual fun <T : Any> scopeLocal(fallback: () -> T): ScopeLocal<T> = ScopeLocal(fallback)
