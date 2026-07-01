package io.github.fukusaka.keel.scope

import io.github.fukusaka.keel.apple.DispatchQueueLocal

/**
 * Apple [ScopeLocal] actual — a thin wrapper **composing** [DispatchQueueLocal]
 * over [ThreadLocalScopeLocal], because Apple hosts two unrelated EventLoop
 * execution models that share this source set:
 *
 * - **NWConnection** runs on GCD serial queues that migrate across worker
 *   pthreads. Per-queue isolation requires [DispatchQueueLocal.install] on each
 *   connection queue; a `@ThreadLocal` would alias state across queues.
 * - **kqueue** runs on a raw pthread pinned for the EventLoop's lifetime, with
 *   no GCD queue installed. There, `@ThreadLocal` is the correct primitive.
 *
 * So [current] returns the installed per-queue value on an NWConnection
 * queue, and otherwise falls back to a per-pthread [ThreadLocalScopeLocal]
 * slot — giving kqueue (and any off-GCD caller) correct per-thread isolation.
 * The caller's `fallback` is the value supplier for both layers; isolation is
 * provided by this factory, not by the caller (matching the JVM / Linux / JS
 * actuals). Mirrors the hand-rolled `DispatchQueueLocal(fallback = { @ThreadLocal })`
 * in `HttpHeadersPool`.
 *
 * Per-queue install is NWConnection-specific and not on [ScopeLocal]; an
 * Apple consumer that needs [DispatchQueueLocal.install] (e.g.
 * `installScopedHeadersPool`) reaches it through [dispatchQueueLocal] — an
 * Apple-only member, invisible to `commonMain` code compiled against the
 * `expect class` — rather than casting a [scopeLocal]-obtained instance.
 *
 * [dispatchQueueLocal] holds the concrete [DispatchQueueLocal] — a `final`
 * class — so [current] / [isScopedHere] forward to it as ordinary final-class
 * calls, not through an interface; see [ScopeLocal]'s class KDoc for why that
 * distinction is the point of this type being an `expect class`.
 */
actual class ScopeLocal<T : Any> internal constructor(fallback: () -> T) {
    /**
     * The composite's queue-scoped layer, for Apple engines that need
     * [DispatchQueueLocal.install] on a `scopeLocal`-obtained slot (e.g.
     * `installScopedHeadersPool`). Not part of the common [ScopeLocal]
     * contract — only reachable from Apple-specific source sets.
     */
    val dispatchQueueLocal = DispatchQueueLocal(fallback = ThreadLocalScopeLocal(fallback)::current)

    actual fun current(): T = dispatchQueueLocal.current()

    actual fun isScopedHere(): Boolean = dispatchQueueLocal.isScopedHere()
}

/** Apple [scopeLocal] actual: constructs the composite [ScopeLocal] above. */
actual fun <T : Any> scopeLocal(fallback: () -> T): ScopeLocal<T> = ScopeLocal(fallback)
