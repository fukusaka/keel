package io.github.fukusaka.keel.scope

/**
 * Linux [scopeLocal] actual: returns a [ThreadLocalScopeLocal], the
 * Kotlin/Native `@ThreadLocal`-backed per-OS-thread slot. Linux has no GCD, so
 * each EventLoop pthread owns its slot directly. [fallback] supplies each
 * thread's value on first read.
 */
actual fun <T : Any> scopeLocal(fallback: () -> T): ScopeLocal<T> = ThreadLocalScopeLocal(fallback)
