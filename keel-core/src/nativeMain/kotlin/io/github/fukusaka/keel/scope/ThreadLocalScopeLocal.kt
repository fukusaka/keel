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
 * `NativeConcurrencyProbeTest`) and the runtime handles teardown cleanup. A
 * `pthread_setspecific` wrapper was prototyped as a "spec-strict" alternative
 * but rejected — its key destructor cannot dispose the value's `StableRef` from
 * a thread-teardown callback without crashing the K/N runtime.
 */
@ThreadLocal
private val perThreadStore: HashMap<Any, Any> = HashMap()

internal class ThreadLocalScopeLocal<T : Any>(private val fallback: () -> T) : ScopeLocal<T> {
    private val key = Any()

    @Suppress("UNCHECKED_CAST")
    override fun current(): T = perThreadStore.getOrPut(key) { fallback() } as T

    override fun isScopedHere(): Boolean = perThreadStore.containsKey(key)
}
