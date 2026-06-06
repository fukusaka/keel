package io.github.fukusaka.keel.scope

import kotlin.native.concurrent.ThreadLocal

/**
 * Per-OS-thread [ScopeLocal] backed by a Kotlin/Native [@ThreadLocal][ThreadLocal]
 * store keyed by slot identity. Shared by the Linux actual (used directly) and
 * the Apple actual (used as the off-GCD-queue fallback of `DispatchQueueLocal`).
 *
 * One [perThreadStore] map per OS thread — including the raw pthreads keel's
 * epoll / kqueue / io_uring EventLoops run on — holds each slot's value for
 * that thread. Keying by the slot's identity object lets a single `@ThreadLocal`
 * declaration back arbitrarily many [scopeLocal] instances.
 *
 * `@ThreadLocal` is Kotlin/Native's runtime-managed per-thread primitive: it
 * gives per-pthread isolation (proven on raw EventLoop pthreads by
 * `NativeConcurrencyProbeTest`) and the runtime handles teardown cleanup.
 *
 * **Cost, and why this is a HashMap rather than something faster.** Each
 * [current] is a `HashMap.getOrPut` keyed by the slot's identity. That is
 * roughly an **order of magnitude slower than a dedicated *static* `@ThreadLocal`
 * val** — the price of one static `@ThreadLocal` declaration backing arbitrarily
 * many [scopeLocal] instances: K/N `@ThreadLocal` must annotate a compile-time
 * top-level declaration, so there is no fast *per-instance* thread-local slot to
 * allocate dynamically. The alternatives were prototyped and measured, and are
 * all worse or unusable:
 * - `pthread_setspecific` (a per-instance `PthreadLocal`) is **slower still than
 *   the HashMap** — a C extern call plus a `StableRef` dereference. K/N has no
 *   equivalent of a fast per-instance `java.lang.ThreadLocal` (the JVM
 *   `ThreadLocal.get` intuition does not carry over). And its only clean
 *   teardown — a `pthread_key_create` destructor disposing the value's
 *   `StableRef` on thread exit — crashes the K/N runtime: the destructor fires
 *   after the runtime has deinitialized the exiting thread, forcing an illegal
 *   runtime re-init mid-teardown (`initRuntimeIfNeeded` is an error in the new
 *   memory model).
 * - A 1-entry `@ThreadLocal` fast-path cache of the last-accessed `(key,value)`
 *   roughly halves the gap to the static val, but degrades to the HashMap once
 *   two slots are hot on the same thread.
 *
 * **Mitigation for hot per-request consumers.** Resolve [current] **once per
 * execution scope** (per connection / per EventLoop) and hold the returned
 * value, instead of calling [current] per operation — a caller-side cache costs
 * **less than the original static val**. `HttpHeadersPool` / `HttpRequestDecoder`
 * do exactly this: the decoder resolves its pool stack once per connection on
 * the EventLoop scope and reuses it for every per-request borrow / release.
 *
 * Indicative figures (Kotlin/Native 2.3.20 release, raw pthread, AMD Ryzen
 * 32-core / Linux 6.14): HashMap ~6.7 ns, static `@ThreadLocal` val ~0.6 ns,
 * `pthread_setspecific` ~9.7 ns, fast-path cache ~2.5 ns, caller-cached
 * ~0.17 ns/call. Hardware- and version-dependent; the ratios are the durable
 * part. Full method and per-platform numbers are in the project's research log.
 */
@ThreadLocal
private val perThreadStore: HashMap<Any, Any> = HashMap()

internal class ThreadLocalScopeLocal<T : Any>(private val fallback: () -> T) : ScopeLocal<T> {
    private val key = Any()

    @Suppress("UNCHECKED_CAST")
    override fun current(): T = perThreadStore.getOrPut(key) { fallback() } as T

    override fun isScopedHere(): Boolean = perThreadStore.containsKey(key)
}
