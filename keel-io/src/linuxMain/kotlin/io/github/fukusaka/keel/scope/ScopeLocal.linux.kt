// MatchingDeclarationName false-positive (file-level, so @file:Suppress):
// detekt's metadata-compile analysis of this intermediate source set
// (linuxMain) expects the file's basename before the final `.kt` to equal the
// class name, which the project's mandated `{Name}.{platform}.kt` actual-file
// convention never satisfies. Leaf source sets (e.g. jvmMain's
// ScopeLocal.jvm.kt) do not trigger this; only actual classes declared in an
// intermediate source set do.
@file:Suppress("MatchingDeclarationName")

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
