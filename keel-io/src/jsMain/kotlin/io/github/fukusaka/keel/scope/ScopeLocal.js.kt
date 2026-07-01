package io.github.fukusaka.keel.scope

/**
 * JS [ScopeLocal] actual backed by a lazily-initialized singleton.
 *
 * Node.js runs on a single execution context, so a per-scope value collapses
 * to one process-wide value. [fallback] is invoked at most once, on the first
 * [current] call, and the result is retained for the lifetime of the slot.
 */
actual class ScopeLocal<T : Any> internal constructor(private val fallback: () -> T) {
    private var value: T? = null

    actual fun current(): T = value ?: fallback().also { value = it }

    actual fun isScopedHere(): Boolean = true
}

/**
 * JS [scopeLocal] actual: returns a lazily-initialized singleton slot (see
 * [ScopeLocal]). Node.js is single-threaded, so the per-scope value
 * collapses to one process-wide value resolved from [fallback] on first read.
 */
actual fun <T : Any> scopeLocal(fallback: () -> T): ScopeLocal<T> = ScopeLocal(fallback)
