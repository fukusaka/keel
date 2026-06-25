package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.apple.DispatchQueueLocal
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.darwin.dispatch_queue_t
import platform.posix.getenv
import kotlin.experimental.ExperimentalNativeApi

/**
 * Installs a per-queue scoped [HttpHeaders] pool stack on [queue].
 *
 * Every NWConnection engine should call this on each per-connection serial
 * dispatch queue it creates, before the first callback runs. After the call,
 * all [HttpHeadersPool.borrow] / [HttpHeaders.release] invocations executed on
 * [queue] — or any block dispatched onto it — operate on a stack private to
 * [queue]. When [queue] is finally released, GCD invokes the
 * [DispatchQueueLocal] destructor which disposes the backing
 * [kotlinx.cinterop.StableRef]; pooled instances then become eligible for the
 * K/N GC.
 *
 * Idempotent per queue: calling [installScopedHeadersPool] twice on the same
 * queue replaces the previous scoped stack. Production code should only invoke
 * it once per queue, immediately after queue creation.
 *
 * This is the engine-side hook that closes the cross-queue header-pool race.
 * Engines that do not call it inherit the [headersPoolScope] `@ThreadLocal`
 * fallback, correct as long as their EventLoop is pthread-pinned (every keel
 * engine today is, except NWConnection).
 *
 * The cast is safe: [headersPoolScope] is built by `scopeLocal { ... }`, whose
 * Apple actual is always a [DispatchQueueLocal] (the composite's outer layer).
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("UNCHECKED_CAST")
fun installScopedHeadersPool(queue: dispatch_queue_t) {
    (headersPoolScope as DispatchQueueLocal<ArrayDeque<HttpHeaders>>).install(queue, ArrayDeque())
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal actual fun readBypassEnvVar(): Boolean =
    getenv("KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS")?.toKString() == "1"
