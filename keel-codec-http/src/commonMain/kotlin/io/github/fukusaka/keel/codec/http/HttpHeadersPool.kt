package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.scope.ScopeLocal
import io.github.fukusaka.keel.scope.scopeLocal
import kotlin.concurrent.Volatile

/**
 * Thread-local pool of reusable [HttpHeaders] instances.
 *
 * Reduces per-request allocation by recycling the [HttpHeaders] object
 * **and** its internal storage (the `ArrayList<HeaderEntry>` backing
 * array + the `IntArray` hash bucket head + the `IntArray` per-entry
 * bucket-chain links) across requests. A pool hit costs one stack pop;
 * a miss (cold start or pool drained) costs one fresh [HttpHeaders]
 * construction. The recycled instance is reset by
 * [HttpHeaders.release] before it returns here.
 *
 * **Thread-local, not global.** keel's multi-worker EventLoop engines
 * (NIO / epoll / io_uring) round-robin accepted connections across a
 * `workerGroup` of `availableProcessors()` threads by default; each
 * connection's parser `borrow`s and the handler `release`s on that
 * connection's owning EventLoop thread. A single global pool backed by
 * a `kotlin.collections.ArrayDeque` corrupts under the resulting
 * concurrent `borrow` / `release` (the `ArrayDeque` is not thread-safe;
 * the symptom was `ArrayIndexOutOfBoundsException` killing worker
 * threads). The pool is therefore backed by a per-scope stack via
 * [headersPoolScope] / [headersPoolStack] — a [ScopeLocal] whose binding is
 * `java.lang.ThreadLocal` on JVM, a `@ThreadLocal` slot on Native, a plain
 * singleton on single-threaded JS, and a per-queue `DispatchQueueLocal` over a
 * `@ThreadLocal` fallback on Apple (NWConnection installs a per-connection-queue
 * stack via [installScopedHeadersPool]; other engines use the per-pthread slot).
 *
 * Per-thread is equivalent to per-EventLoop here (keel confines each
 * EventLoop to one thread), so the recycling benefit is preserved:
 * each EventLoop thread reuses instances across all the connections it
 * serves, with zero locking and zero cross-thread contention. A
 * strategy microbench (`HttpHeadersPoolStrategyBenchmark`) on a 32-core
 * box measured the thread-local approach at ~376 M cycles/s with the
 * full alloc reduction retained, versus ~3.7 M cycles/s for a
 * lock-guarded global pool (contention collapse) at the same thread
 * count. The choice of `@ThreadLocal` is validated on Kotlin/Native
 * pthread-created threads — the actual EventLoop thread mechanism — by
 * `NativeConcurrencyProbeTest`.
 *
 * **Memory bound.** Total pooled storage is
 * `MAX_POOLED × per-instance footprint × EventLoop-thread count`. The
 * per-instance footprint is dominated by the retained
 * `bucketHead: IntArray(64)` (~272 B) plus the grown `bucketNext` /
 * `entries` backing arrays — roughly ~600 B at the CDN-typical header
 * count. With [MAX_POOLED] = 64 and `availableProcessors()` worker
 * threads this is about `64 × 600 B × N` (≈ 1.2 MiB on a 32-core box).
 * [MAX_POOLED] caps each thread's stack; sizing follows Netty's
 * `Recycler` model (per-thread cap, total scales with thread count),
 * though keel's value is far smaller because Netty's 4096 default is
 * for tiny `ByteBuf` handles whereas each [HttpHeaders] is ~600 B and
 * a single EventLoop thread's working set is the connections it serves
 * concurrently, not thousands.
 *
 * **Not** a buffer allocator: codec headers never participate in
 * zero-copy DMA, so the pool deliberately holds heap-managed objects
 * and does not reach into the I/O `BufferAllocator`. Every other
 * production HTTP codec we surveyed (Netty 4.1, Jetty 12, Hyper,
 * Ktor CIO) follows the same boundary — the codec layer never pulls
 * header storage from the I/O buffer pool.
 */
internal object HttpHeadersPool {

    /**
     * Cross-queue header-pool investigation toggle. When `true`, every [borrow] returns a
     * fresh instance and every [giveBack] drops the instance — the pool
     * is entirely bypassed and `@ThreadLocal` `nativeStack` never accumulates.
     *
     * Used to bisect the cross-queue header-pool crash's causal chain: if disabling the
     * pool eliminates the residual 3% `HttpHeaders.resetForReuse` SIGSEGV
     * observed on `server-http × nwconnection × {mbedtls,openssl}`, the
     * defect lies on the pool-reuse path (cross-connection / cross-worker
     * instance handoff); if the crash rate is unchanged, the pool is
     * innocent and the race lives elsewhere (response writer, NWConnection
     * internal queue, etc.).
     *
     * The flag is read on every borrow/giveBack from a `@Volatile` Boolean
     * (negligible cost — single byte load, no atomic op). It is initialised
     * once at object init from [readBypassEnvVar] — `true` iff
     * `KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS=1` is set in the process
     * environment, `false` otherwise — so a benchmark binary launched with
     * that env var bypasses the pool with no code change. Tests toggle it
     * at runtime via [setBypassPool].
     *
     * **Not exposed via public API.** Even after the cross-queue header-pool root cause was
     * fixed (per-NWConnection-queue scoped pool, PR #627), this flag is
     * retained as a diagnostic / benchmarking lever — an A/B switch to
     * isolate the pool from any future crash's causal chain. Keeping it a
     * private flag (plus the `internal` [setBypassPool] / [isBypassPool]
     * accessors) avoids contaminating the public surface or accidentally
     * landing as a long-term tuning knob.
     */
    @Volatile
    private var bypassPool: Boolean = readBypassEnvVar()

    /**
     * Test/bench-only setter for [bypassPool]. The mutator name is
     * deliberately verbose so a grep for `setBypassPool` enumerates every
     * call site; production code must never invoke it.
     */
    internal fun setBypassPool(value: Boolean) {
        bypassPool = value
    }

    /** Test/bench-only read of [bypassPool]. */
    internal fun isBypassPool(): Boolean = bypassPool

    /**
     * Returns a reset [HttpHeaders] ready for `add`. Either a pooled
     * instance (with its `ArrayList` backing + `IntArray` hash bucket
     * already allocated) or a fresh construction if this thread's pool
     * is empty. When [bypassPool] is set, always returns a fresh
     * construction regardless of the per-thread stack.
     */
    fun borrow(): HttpHeaders =
        // Plain path: resolve the current scope's stack to pop from, but record
        // no caller-cache handle — [giveBack] looks up the stack again at
        // release. For a borrow whose release may resolve a different scope
        // than the borrow did (so capture-at-borrow would be unsafe). The
        // decoders take theirs on the read path through [borrowFrom]; this is
        // the safe default for any borrower that cannot make that guarantee,
        // and it is reachable from outside the module through
        // [HttpHeaders.borrow].
        borrowImpl(if (bypassPool) null else headersPoolStack(), handle = null)

    /**
     * Borrows from [stack] directly and records it as the instance's caller-cache
     * handle, so [giveBack] (from [HttpHeaders.release]) returns it without a
     * per-call [headersPoolScope] lookup. The caller must have resolved [stack]
     * on the same execution scope where the instance will be released — i.e. the
     * connection's EventLoop scope. This is the decoders' only borrow path:
     * they resolve [stack] once per connection, on the read path, and reuse it.
     */
    fun borrowFrom(stack: ArrayDeque<HttpHeaders>): HttpHeaders =
        borrowImpl(if (bypassPool) null else stack, handle = stack)

    private fun borrowImpl(stack: ArrayDeque<HttpHeaders>?, handle: ArrayDeque<HttpHeaders>?): HttpHeaders {
        val instance = if (stack == null || stack.isEmpty()) {
            HttpHeaders().also { it.markPooled() }
        } else {
            // A recycled instance keeps `pooled = true` from its first
            // construction; mark it checked out again below for this borrow so
            // the double-release guard arms for the new lifecycle.
            stack.removeLast()
        }
        instance.markCheckedOut()
        instance.poolStack = if (bypassPool) null else handle
        return instance
    }

    /**
     * Returns [headers] to the calling thread's pool. Called from
     * [HttpHeaders.release] after [HttpHeaders.resetForReuse] has wiped
     * the per-request state. Callers must not retain the reference.
     *
     * Two reasons the instance may be dropped (eligible for GC) instead
     * of being recycled:
     *
     * 1. The pool is already at [MAX_POOLED] capacity (the per-thread
     *    cap shaped after Netty's `Recycler` model).
     * 2. The instance's internal [HttpHeaders.slotCapacity] has grown
     *    past [SHRINK_CAPACITY_THRESHOLD]. A request flooded with a
     *    malicious number of headers would otherwise leave the
     *    grown-back-`IntArray` slot storage in the pool, where every
     *    subsequent borrower inherits it — a single attacker request
     *    poisons the per-thread pool for the lifetime of the worker.
     *    Dropping over-sized instances bounds the per-EventLoop
     *    pooled footprint to roughly [MAX_POOLED] × the slot capacity
     *    a normal request grows to. The dropped instance is reclaimed
     *    by the GC; the pool refills on the next miss with a
     *    fresh-from-allocator default-sized instance.
     */
    fun giveBack(headers: HttpHeaders) {
        if (bypassPool) return
        // Prefer the caller-cache handle recorded at borrow (no lookup); fall
        // back to resolving the current scope's stack for the plain borrow path.
        val stack = headers.poolStack ?: headersPoolStack()
        headers.poolStack = null
        if (headers.slotCapacity > SHRINK_CAPACITY_THRESHOLD) return
        if (stack.size < MAX_POOLED) stack.addLast(headers)
    }

    /** Visible for tests — drops the calling thread's pooled instances. */
    internal fun clear() {
        headersPoolStack().clear()
    }

    /** Visible for tests — number of instances pooled on the calling thread. */
    internal fun size(): Int = headersPoolStack().size

    /**
     * Cap on the number of instances retained per EventLoop thread.
     * The working set of one EventLoop thread is the number of
     * connections it serves with an in-flight request head at once; 64
     * covers typical per-thread connection concurrency. Total pooled
     * memory is this value times the worker-thread count (see the
     * class KDoc memory-bound note).
     */
    internal const val MAX_POOLED: Int = 64

    /**
     * Slot-capacity ceiling above which a recycled instance is dropped
     * instead of being pooled (see [giveBack]).
     *
     * Sized to `2 × HttpHeaderLimitsConfig.DEFAULT_MAX_HEADER_COUNT`
     * (= 200 slots, ~5 KB at the 5-int stride) so a request that grows
     * past *twice* the default header cap before the per-request count
     * cap fires (the cap fires only against the configured value, not
     * against this pool-side threshold) still does not poison the pool
     * across all subsequent requests. The shrink is one-shot per
     * instance: a normal request that never grew past the default
     * `INITIAL_ENTRY_CAPACITY` allocation is recycled as before.
     */
    internal const val SHRINK_CAPACITY_THRESHOLD: Int = 200
}

/**
 * Per-scope [HttpHeaders] pool stack, bound to the calling execution scope by
 * [ScopeLocal]. The returned deque is confined to the current scope, so
 * [HttpHeadersPool] operates on it without any locking. The platform binding
 * (via [scopeLocal]) is `java.lang.ThreadLocal` on JVM, a `@ThreadLocal` slot
 * on Linux, a singleton on JS, and a `DispatchQueueLocal`-over-`@ThreadLocal`
 * composite on Apple — so an NWConnection per-connection serial queue gets a
 * private stack via [installScopedHeadersPool], while kqueue / epoll / io_uring
 * / nio / netty pthreads fall back to a per-thread slot (each is pthread-pinned,
 * so per-thread is per-EventLoop).
 */
internal val headersPoolScope: ScopeLocal<ArrayDeque<HttpHeaders>> = scopeLocal { ArrayDeque() }

/** Returns the [HttpHeaders] pool stack for the current execution scope. */
internal fun headersPoolStack(): ArrayDeque<HttpHeaders> = headersPoolScope.current()

/**
 * Reads `KEEL_BENCH_HTTP_HEADERS_POOL_BYPASS` from the platform's
 * process environment. Returns `true` when the value is `"1"`,
 * `false` otherwise (including "unset"). Used by [HttpHeadersPool] to
 * initialise the cross-queue header-pool investigation bypass flag at class-init time.
 *
 * Single env var probe at codec-http init; the value is captured in
 * the `bypassPool` field and not re-read.
 */
internal expect fun readBypassEnvVar(): Boolean
