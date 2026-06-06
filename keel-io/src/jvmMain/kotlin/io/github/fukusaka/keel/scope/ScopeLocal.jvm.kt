package io.github.fukusaka.keel.scope

/**
 * JVM [ScopeLocal] actual backed by [java.lang.ThreadLocal].
 *
 * The scope is the OS thread. [ThreadLocal.withInitial] auto-initializes the
 * value once per thread from [fallback], so there is no separate install path
 * and [isScopedHere] is always `true`.
 */
private class ThreadLocalScopeLocal<T : Any>(fallback: () -> T) : ScopeLocal<T> {
    private val threadLocal: ThreadLocal<T> = ThreadLocal.withInitial(fallback)

    override fun current(): T = threadLocal.get()

    override fun isScopedHere(): Boolean = true
}

/**
 * JVM [scopeLocal] actual: returns a [java.lang.ThreadLocal]-backed slot
 * (see [ThreadLocalScopeLocal]). The scope is the OS thread; [fallback] is the
 * thread-local's initial supplier.
 */
actual fun <T : Any> scopeLocal(fallback: () -> T): ScopeLocal<T> = ThreadLocalScopeLocal(fallback)
