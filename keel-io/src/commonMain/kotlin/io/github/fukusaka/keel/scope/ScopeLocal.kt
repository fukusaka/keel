package io.github.fukusaka.keel.scope

/**
 * Storage for a value of type [T] bound to a logical execution scope rather
 * than to an object reference. The cross-platform generalization of "name a
 * slot, read it from inside the scope that owns it", where each platform binds
 * the slot to its own native scope primitive.
 *
 * ```
 * ScopeLocal<T>                       scope bound to
 * ------------                        --------------
 * appleMain  → DispatchQueueLocal     a GCD dispatch queue, over a per-pthread
 *                                      @ThreadLocal fallback (NWConnection + kqueue)
 * linuxMain  → @ThreadLocal slot      an OS thread (Kotlin/Native @ThreadLocal)
 * jvmMain    → java.lang.ThreadLocal  an OS thread
 * jsMain     → singleton              the single JS execution context
 * ```
 *
 * The Apple actual is a **composite** because that source set hosts two
 * EventLoop models: NWConnection (GCD serial queues — per-queue
 * `DispatchQueueLocal.install`) and kqueue (raw pthreads — the `@ThreadLocal`
 * fallback). A single `scopeLocal` call serves both.
 *
 * Only the **read** side is common. [current] resolves the value for the
 * scope currently executing this code; [isScopedHere] reports whether that
 * scope has an explicitly installed value. The **install/write** side is
 * intentionally *not* exposed here because the three models are incompatible:
 * thread-locals auto-initialize per thread (no install), `DispatchQueueLocal`
 * installs per queue, and a block-scoped binding would install per dynamic
 * extent. Each actual exposes its own install path where one exists (e.g.
 * `DispatchQueueLocal.install(queue, value)`); auto-init actuals expose none.
 *
 * Obtain an instance through the [scopeLocal] factory with a [fallback] that
 * produces the value for scopes that have no installed value (the common case
 * for auto-init platforms, and the off-queue path on Apple).
 *
 * **Why an abstraction.** keel runs EventLoops on raw pthreads it creates
 * itself, and — on Apple — on GCD serial queues that migrate across worker
 * pthreads. Per-scope state (object pools, per-connection counters) needs the
 * scope primitive that matches each platform's execution model; this
 * `expect class` lets common code consume that state uniformly while each
 * platform supplies the correct binding.
 *
 * **`expect class`, not an interface.** `current()` is called on the hot path
 * of pooled-buffer consumers (`HttpHeadersPool` / `HttpRequestDecoder`). A
 * micro-benchmark investigation found that Kotlin/Native's *genuinely
 * polymorphic* interface dispatch (a call site with 2+ live implementers
 * reachable in the compiled binary) costs real, measurable overhead — roughly
 * 4x a concrete no-interface call on macOS arm64 — while a *monomorphic*
 * interface call (one implementer resolvable at that call site, even if
 * others exist elsewhere in the binary) is noticeably cheaper but still not
 * free. `ScopeLocal`'s per-target `expect`/`actual` factory already ensures
 * exactly one implementation is ever linked into a given compiled target —
 * the best case for the compiler to devirtualize an interface call — but that
 * is an optimization the compiler *happens* to apply, not a language
 * guarantee. An `expect class` removes the interface layer structurally: each
 * compiled target sees a single concrete class with no possible second
 * implementer, so the call can never fall back to genuine (costly)
 * polymorphic dispatch regardless of the optimizer's behavior. Each actual
 * (`ScopeLocal.linux.kt` / `.jvm.kt` / `.js.kt` / `.apple.kt`) still
 * implements the same per-platform binding described above; only the removed
 * interface layer changed.
 *
 * @param T the value type bound per scope. Must be a non-null reference type.
 */
expect class ScopeLocal<T : Any> {
    /**
     * Returns this slot's value for the scope currently executing this code:
     * the installed value if the current scope had one installed, otherwise a
     * value lazily produced by the `fallback` supplied to [scopeLocal] and
     * cached for that scope.
     *
     * **Scope granularity.** The fallback path caches per OS-thread (per the
     * `@ThreadLocal` / `ThreadLocal` slot; once total on JS). This is correct
     * for pthread-pinned contexts (every keel EventLoop, including kqueue). On
     * Apple, a migrating GCD serial queue must therefore `install` a per-queue
     * value to get per-queue rather than per-pthread semantics; without an
     * install it falls back to the per-pthread slot. `fallback` should produce
     * an independent value per call (e.g. `{ ArrayDeque() }`); it is invoked at
     * most once per scope.
     */
    fun current(): T

    /**
     * Returns `true` when the current execution scope holds a resolved value
     * for this slot — best-effort, primarily a diagnostic. Its discriminating
     * use is on Apple, where it reports `false` on the off-queue fallback path
     * and `true` only inside a block on a queue that had `install` called
     * (the basis for "assert this callback runs on its connection queue").
     * JVM (`ThreadLocal.withInitial`) reports `true` since a value is always
     * resolvable; the lazy native/singleton actuals report `true` once the
     * scope has resolved its value via [current]. Intended for assertions and
     * diagnostics, not control flow.
     */
    fun isScopedHere(): Boolean
}

/**
 * Creates a [ScopeLocal] slot whose value is produced by [fallback] for any
 * scope that has no explicitly installed value.
 *
 * The returned instance is the platform's native scope primitive: a
 * `DispatchQueueLocal`-over-`@ThreadLocal` composite on Apple, a Kotlin/Native
 * `@ThreadLocal`-backed slot on Linux, a `java.lang.ThreadLocal`-backed slot on
 * JVM, a lazily-initialized singleton on JS. Declare one instance per logical
 * slot (typically a process-lifetime value); its identity is the slot key.
 *
 * @param fallback produces the value used by [ScopeLocal.current] when the
 *   caller's scope has no installed value. Invoked lazily and at most once per
 *   scope (the result is cached), so it should produce an independent value per
 *   call — the canonical usage is a fresh container such as `{ ArrayDeque() }`.
 *   See [ScopeLocal.current] for scope granularity.
 */
expect fun <T : Any> scopeLocal(fallback: () -> T): ScopeLocal<T>
