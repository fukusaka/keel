package io.github.fukusaka.keel.codec.http

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.concurrent.ThreadLocal
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.darwin.dispatch_get_specific
import platform.darwin.dispatch_queue_set_specific
import platform.darwin.dispatch_queue_t
import platform.posix.getenv

/**
 * Per-thread pool stack fallback backed by Kotlin/Native [@ThreadLocal][ThreadLocal].
 *
 * Used as the **fallback** when no scoped pool has been installed on the
 * current dispatch queue (e.g. POSIX engines' EventLoop threads created
 * via `pthread_create`, where each engine runs on a single thread and
 * the per-thread invariant matches the recycling assumption).
 *
 * For NWConnection (GCD-based) engines a per-queue scoped stack is
 * installed via [installScopedHeadersPool]; see the K56b investigation
 * notes for why the @ThreadLocal fallback is not safe under GCD
 * worker-thread migration (a single pthread can serve blocks from
 * multiple connections' queues, and the per-thread pool then leaks
 * instances across connection boundaries — the K56b cross-queue
 * aliasing race).
 */
@ThreadLocal
private val nativeStack: ArrayDeque<HttpHeaders> = ArrayDeque()

/**
 * Marker pointer used as the `dispatch_queue_specific_key` for the
 * per-queue scoped HttpHeaders pool. Identity-only — the [Any] target
 * value is never read; only the pointer identity matters.
 *
 * Backed by a [StableRef] so the pointer survives across the entire
 * process lifetime. The ref is intentionally never disposed — the key
 * is a process singleton, freed only on process exit.
 */
@OptIn(ExperimentalForeignApi::class)
private val scopedPoolKeyRef: StableRef<Any> = StableRef.create(Any())

@OptIn(ExperimentalForeignApi::class)
private val scopedPoolKey: COpaquePointer = scopedPoolKeyRef.asCPointer()

/**
 * Destructor invoked by GCD when a queue that had a scoped pool
 * installed is being released. Disposes the [StableRef] that owns the
 * `ArrayDeque<HttpHeaders>` for that queue, so its memory is reclaimed.
 *
 * Must be a [staticCFunction] (no captures) since GCD calls it from C.
 */
@OptIn(ExperimentalForeignApi::class)
private val scopedPoolDestructor = staticCFunction<COpaquePointer?, Unit> { ptr ->
    if (ptr != null) {
        ptr.asStableRef<ArrayDeque<HttpHeaders>>().dispose()
    }
}

/**
 * Returns the [HttpHeaders] pool stack for the current execution
 * context. Priority:
 *
 * 1. **Per-queue scoped stack** if the current GCD dispatch queue has
 *    one installed via [installScopedHeadersPool]. Used by NWConnection
 *    so each connection's serial queue has its own stack, eliminating
 *    the K56b cross-queue aliasing race.
 * 2. **`@ThreadLocal` fallback** otherwise. Used by POSIX EventLoop
 *    engines (kqueue / epoll / io_uring) where each EventLoop is pinned
 *    to one pthread and the per-thread invariant is valid.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun headersPoolStack(): ArrayDeque<HttpHeaders> {
    val specific = dispatch_get_specific(scopedPoolKey)
    if (specific != null) {
        return specific.asStableRef<ArrayDeque<HttpHeaders>>().get()
    }
    return nativeStack
}

/**
 * Installs a per-queue scoped [HttpHeaders] pool stack on [queue].
 *
 * Each NWConnection engine should call this on every per-connection
 * serial dispatch queue it creates, before the first callback runs.
 * After the call:
 *
 * - All [HttpHeadersPool.borrow] / [HttpHeaders.release] invocations
 *   executed on [queue] (or any block dispatched onto it) operate on a
 *   stack private to [queue].
 * - When [queue] is released by its last owner, GCD invokes the
 *   destructor which disposes the [StableRef] backing the stack. Any
 *   pooled instances become garbage and are reclaimed by the K/N GC.
 *
 * The function is **idempotent per queue** — calling it twice on the
 * same queue replaces the previous stack and disposes the old [StableRef]
 * via GCD's destructor. Production code should only call it once per
 * queue, immediately after queue creation.
 */
@OptIn(ExperimentalForeignApi::class)
fun installScopedHeadersPool(queue: dispatch_queue_t) {
    val stack = ArrayDeque<HttpHeaders>()
    val stackRef = StableRef.create(stack)
    dispatch_queue_set_specific(queue, scopedPoolKey, stackRef.asCPointer(), scopedPoolDestructor)
}

@OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)
internal actual fun readBypassEnvVar(): Boolean =
    getenv("KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS")?.toKString() == "1"
