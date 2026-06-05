package io.github.fukusaka.keel.scope

import io.github.fukusaka.keel.apple.DispatchQueueLocal

/**
 * Apple [scopeLocal] actual — a **composite** of [DispatchQueueLocal] over
 * [ThreadLocalScopeLocal], because Apple hosts two unrelated EventLoop
 * execution models that share this source set:
 *
 * - **NWConnection** runs on GCD serial queues that migrate across worker
 *   pthreads. Per-queue isolation requires [DispatchQueueLocal.install] on each
 *   connection queue; a `@ThreadLocal` would alias state across queues.
 * - **kqueue** runs on a raw pthread pinned for the EventLoop's lifetime, with
 *   no GCD queue installed. There, `@ThreadLocal` is the correct primitive.
 *
 * So `current()` returns the installed per-queue value on an NWConnection
 * queue, and otherwise falls back to a per-pthread [ThreadLocalScopeLocal]
 * slot — giving kqueue (and any off-GCD caller) correct per-thread isolation.
 * The caller's [fallback] is the value supplier for both layers; isolation is
 * provided by this factory, not by the caller (matching the JVM / Linux / JS
 * actuals). Mirrors the hand-rolled `DispatchQueueLocal(fallback = { @ThreadLocal })`
 * in `HttpHeadersPool`.
 *
 * Per-queue install is NWConnection-specific and not on the [ScopeLocal]
 * interface; an Apple consumer installs via the concrete [DispatchQueueLocal].
 */
actual fun <T : Any> scopeLocal(fallback: () -> T): ScopeLocal<T> =
    DispatchQueueLocal(fallback = ThreadLocalScopeLocal(fallback)::current)
