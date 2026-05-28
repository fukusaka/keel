package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.apple.DispatchQueueLocal
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ThreadLocal
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.darwin.dispatch_queue_t
import platform.posix.getenv

/**
 * Per-thread pool stack **fallback** backed by Kotlin/Native
 * [@ThreadLocal][ThreadLocal].
 *
 * Used when [poolLocal] has no scoped value for the current dispatch
 * queue — i.e. the caller is on a non-GCD pthread (kqueue / epoll /
 * io_uring / nio / netty EventLoop threads), an untagged GCD queue, or
 * the bare main thread. On those execution contexts the per-pthread
 * invariant holds naturally (each EventLoop is pinned to one pthread
 * for its entire lifetime), so the `@ThreadLocal` slot is the correct
 * isolation primitive.
 *
 * The K56b race only fires on **GCD serial queues** because GCD
 * serialises blocks but migrates them across the worker pool — fixed
 * by the per-queue scoped stack via [poolLocal] / [installScopedHeadersPool].
 */
@ThreadLocal
private val nativeStack: ArrayDeque<HttpHeaders> = ArrayDeque()

/**
 * Per-`dispatch_queue_t` scoped pool stack. Each NWConnection
 * per-connection serial queue gets a private stack via
 * [installScopedHeadersPool], so a `borrow` / `release` pair always
 * touches the same queue's stack regardless of which GCD worker
 * pthread happens to execute the block.
 *
 * Falls back to the process-wide [nativeStack] for contexts that did
 * not opt in via [installScopedHeadersPool] (kqueue / epoll / io_uring /
 * nio / netty EventLoop threads).
 */
@OptIn(ExperimentalForeignApi::class)
private val poolLocal: DispatchQueueLocal<ArrayDeque<HttpHeaders>> =
    DispatchQueueLocal(fallback = { nativeStack })

internal actual fun headersPoolStack(): ArrayDeque<HttpHeaders> = poolLocal.current()

/**
 * Installs a per-queue scoped [HttpHeaders] pool stack on [queue].
 *
 * Every NWConnection engine should call this on each per-connection
 * serial dispatch queue it creates, before the first callback runs.
 * After the call, all [HttpHeadersPool.borrow] / [HttpHeaders.release]
 * invocations executed on [queue] — or any block dispatched onto it —
 * operate on a stack private to [queue]. When [queue] is finally
 * released, GCD invokes the [DispatchQueueLocal] destructor which
 * disposes the backing [kotlinx.cinterop.StableRef]; pooled instances
 * then become eligible for the K/N GC.
 *
 * Idempotent per queue: calling [installScopedHeadersPool] twice on
 * the same queue replaces the previous scoped stack (the old
 * `StableRef` is disposed by the destructor before the new pointer
 * binds). Production code should only invoke it once per queue,
 * immediately after queue creation.
 *
 * This is the engine-side hook that closes K56b. Engines that do not
 * call it inherit the [nativeStack] @ThreadLocal fallback, which is
 * correct as long as their EventLoop is pthread-pinned (every keel
 * engine today is, except NWConnection).
 */
@OptIn(ExperimentalForeignApi::class)
fun installScopedHeadersPool(queue: dispatch_queue_t) {
    poolLocal.install(queue, ArrayDeque())
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal actual fun readBypassEnvVar(): Boolean =
    getenv("KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS")?.toKString() == "1"
