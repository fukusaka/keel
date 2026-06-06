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
 * [current] is a `HashMap.getOrPut` keyed by the slot's identity — measured at
 * ~6.7 ns/call on Linux (release, raw pthread), versus ~0.6 ns for a dedicated
 * *static* `@ThreadLocal` val. That ~6 ns is the price of one static
 * `@ThreadLocal` declaration backing arbitrarily many [scopeLocal] instances:
 * K/N `@ThreadLocal` must annotate a compile-time top-level declaration, so
 * there is no fast *per-instance* thread-local slot to allocate dynamically.
 * The alternatives were prototyped and measured, and are all worse or unusable:
 * - `pthread_setspecific` (a per-instance `PthreadLocal`) is **slower still**,
 *   ~9.7 ns/call — a C extern call plus a `StableRef` dereference. K/N has no
 *   equivalent of `java.lang.ThreadLocal` (the JVM `ThreadLocal.get` ~1.5 ns
 *   intuition does not carry over). And its only clean teardown — a
 *   `pthread_key_create` destructor disposing the value's `StableRef` on thread
 *   exit — crashes the K/N runtime: the destructor fires after the runtime has
 *   deinitialized the exiting thread, forcing an illegal runtime re-init
 *   mid-teardown (`initRuntimeIfNeeded` is an error in the new memory model).
 * - A 1-entry `@ThreadLocal` fast-path cache of the last-accessed `(key,value)`
 *   reaches ~2.5 ns, but degrades to the HashMap once two slots are hot on the
 *   same thread.
 *
 * **Mitigation for hot per-request consumers.** Resolve [current] **once per
 * execution scope** (per connection / per EventLoop) and hold the returned
 * value, instead of calling [current] per operation — a caller-side cache
 * reaches ~0.17 ns. `HttpHeadersPool` / `HttpRequestDecoder` do exactly this:
 * the decoder resolves its pool stack once per connection on the EventLoop
 * scope and reuses it for every per-request borrow / release.
 */
@ThreadLocal
private val perThreadStore: HashMap<Any, Any> = HashMap()

internal class ThreadLocalScopeLocal<T : Any>(private val fallback: () -> T) : ScopeLocal<T> {
    private val key = Any()

    @Suppress("UNCHECKED_CAST")
    override fun current(): T = perThreadStore.getOrPut(key) { fallback() } as T

    override fun isScopedHere(): Boolean = perThreadStore.containsKey(key)
}
