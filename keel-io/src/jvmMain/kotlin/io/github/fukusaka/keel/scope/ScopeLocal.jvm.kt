package io.github.fukusaka.keel.scope

/**
 * JVM [ScopeLocal] actual backed by [java.lang.ThreadLocal].
 *
 * The scope is the OS thread. [ThreadLocal.withInitial] auto-initializes the
 * value once per thread from [fallback], so there is no separate install path
 * and [isScopedHere] is always `true`.
 */
actual class ScopeLocal<T : Any> internal constructor(fallback: () -> T) {
    private val threadLocal: ThreadLocal<T> = ThreadLocal.withInitial(fallback)

    actual fun current(): T = threadLocal.get()

    actual fun isScopedHere(): Boolean = true
}

/**
 * JVM [scopeLocal] actual: returns a [java.lang.ThreadLocal]-backed slot
 * (see [ScopeLocal]). The scope is the OS thread; [fallback] is the
 * thread-local's initial supplier.
 */
actual fun <T : Any> scopeLocal(fallback: () -> T): ScopeLocal<T> = ScopeLocal(fallback)
