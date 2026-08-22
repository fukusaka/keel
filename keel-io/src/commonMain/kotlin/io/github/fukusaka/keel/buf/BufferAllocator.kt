package io.github.fukusaka.keel.buf

/**
 * Allocates [IoBuf] instances.
 *
 * Pluggable design: each engine uses the allocator best suited for its
 * platform. Buffer release is handled by the [IoBufOwner] installed on
 * the buffer at [allocate] time — callers simply call [IoBuf.release].
 *
 * ```
 * Allocator              Target        Engine
 * ---------              ------        ------
 * DefaultAllocator       all           all (test/fallback)
 * SlabAllocator          Native        epoll / kqueue
 * PooledDirectAllocator  JVM           NIO / Netty
 * ```
 *
 * io_uring uses its own `ProvidedBufferRing` (in engine-io-uring) for
 * kernel-managed buffer selection, not a [BufferAllocator].
 *
 * **Lifecycle-scoped children**: engines call [createChild] to obtain
 * an owned allocator instance whose lifetime they control. Stateless
 * allocators return `this`. Pool-based allocators return a new instance
 * with its own freelist and chunk arena, so the child can hand out
 * lock-free pool slots and the parent can cascade-close the child on
 * shutdown. Each engine's convention for how often to call this is its
 * own threading model (per EventLoop thread for `epoll` / `kqueue` /
 * `nio` / `io_uring`, once per engine for `NwEngine` / `NodeEngine`
 * where the engine has no per-thread split).
 *
 * **The buffers an engine reads into must carry its platform's backing.** An
 * engine hands read-buffer memory straight to the kernel, through a cast that is
 * unchecked because it runs on every read: `NativePointerAccess` on the Native
 * targets, `NioByteBufferBacking` on the JVM, the `TypedArrayIoBuf` class itself
 * on JS. An implementation used with one must therefore allocate buffers
 * carrying the one its target needs — and so must its children, since a child is
 * what an engine reads through.
 *
 * The epoll and kqueue engines ask once while being built (see
 * `requireNativePointerAccess`) and refuse to start otherwise, naming the
 * allocator. The rest do not yet, so on those the same mistake arrives later and
 * in whatever form that engine's read path gives it. One engine is different in
 * kind rather than merely unchecked: Netty allocates from each channel's own
 * `ByteBufAllocator` and consults this one for its [lifecycleListener] alone, so
 * what it hands out is not read through at all.
 *
 * The codec layer allocates too — the Native compression and TLS codecs take the
 * same pointer from buffers they allocate through a pipeline context — but that
 * context's allocator descends from this one, so the answer is inherited rather
 * than asked again.
 *
 * **Kotlin/JS member stability**: the Kotlin/JS IR backend mangles each
 * interface member to a hash derived from that member's own signature
 * (name + parameter types + return type), not from the interface's
 * total surface. Adding a new default-implemented member to this
 * interface (e.g. the `lifecycleListener` getter added by pluggability
 * item 12 stage B2.5 step 2) therefore does not change the dispatch
 * name of any existing member, so codec APIs that pass a
 * `BufferAllocator` through (notably `Encoder.newSession(allocator,
 * options)` / `Decoder.newSession(allocator, options)`) stay
 * binary-stable across keel versions on Kotlin/JS as long as the
 * caller and the implementation are compiled with the same Kotlin
 * compiler version. Removing or changing the signature of an existing
 * member is a BREAKING change on every target, JS included; the per-
 * member hash convention does not make removals any safer.
 */
interface BufferAllocator {
    /** Allocates a buffer with at least [capacity] bytes. */
    fun allocate(capacity: Int): IoBuf

    /**
     * The [BufferAllocatorLifecycleListener] this allocator chain reports
     * allocate / release events through, for consumers that need per-buffer
     * identity (`TrackingAllocator`, `LeakDetectingAllocator`, leak audits,
     * per-buffer profilers).
     *
     * Default [NoOpLifecycleListener] — implementations that do not record
     * lifecycle events return this so the hot path stays branch-free and
     * monomorphic dispatch on the singleton inlines / elides.
     *
     * **Engine-direct buffer coverage.** Wrapper allocators (e.g.
     * `NettyByteBufAllocator`) that produce `IoBuf` types without a
     * `PoolableIoBuf.owner` seam read this getter from the user-passed
     * `config.allocator` and forward it to their per-engine internal
     * allocator (and to engine-internal `wrapInbound` / equivalent factory
     * paths). This is how a single listener installed on the user's
     * `PooledDirectAllocator(lifecycleListener = …)` reaches the engine's
     * own `NettyByteBufIoBuf` / `RingBufferIoBuf` / `DispatchDataIoBuf`
     * lifecycle events even when those types are not produced by the
     * user's allocator. (Pluggability item 12 stage B2.5.)
     *
     * **Wrapper / decorator convention.** Wrapper allocators
     * (`LeakDetectingAllocator`, `TrackingAllocator`, etc.) typically
     * override this getter to forward their delegate's listener so the
     * chain stays transparent. Wrap with an explicit
     * `PooledDirectAllocator(lifecycleListener = wrapperInstance)` if you
     * want the wrapper itself to be the listener — wrappers are
     * `BufferAllocator` first, listener second.
     *
     * **`createChild` propagation.** Implementations should propagate
     * this listener to the children they produce via
     * [createChild] so a multi-EventLoop engine aggregates into one
     * listener; consequently the listener must be thread-safe when used
     * across EventLoops.
     */
    val lifecycleListener: BufferAllocatorLifecycleListener get() = NoOpLifecycleListener

    /**
     * Wraps a [ByteArray] region as a read-only [IoBuf] view without
     * copying bytes. The returned buffer uses platform-native backing
     * (e.g. pinned pointer on Native, heap ByteBuffer on JVM) so it is
     * compatible with the engine's transport layer.
     *
     * Returns `null` on platforms that do not support zero-copy wrapping.
     * The caller must not mutate [bytes] until the returned buffer is
     * released.
     */
    fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf?

    /**
     * Creates a read-only [IoBuf] view of [length] bytes starting at
     * [offset] in [source]. The returned buffer shares the same backing
     * memory as [source] and uses the same platform-native type, so it
     * is compatible with the engine's transport layer.
     *
     * [source] is [retained][IoBuf.retain] at creation. The returned
     * buffer's [IoBufOwner] is a [SliceOwner] that releases [source]
     * when the slice's reference count reaches zero.
     */
    fun slice(source: IoBuf, offset: Int, length: Int): IoBuf

    /**
     * Hints to the allocator that buffers of exactly [byteSize] bytes
     * will be allocated frequently and that the allocator should keep
     * up to [maxCount] of them ready for reuse.
     *
     * This is a **best-effort hint, not a contract**. Allocators that
     * use a size-class pool may honour it by sizing or registering a
     * cache for the requested size class; allocators that do not
     * structure memory by size class (e.g. [DefaultAllocator],
     * the Netty `PooledByteBufAllocator` wrapper, mimalloc-style
     * heaps) silently ignore the call. Callers must not depend on the
     * hint being honoured for correctness — only for warm-cache /
     * pool-residency optimisation.
     *
     * Allocators that honour the hint:
     * - **Treat duplicate hints for the same `byteSize` as no-ops** —
     *   the first hint wins; subsequent calls do not override the
     *   `maxCount` retained.
     * - **May clamp `maxCount` downward** to respect an internal
     *   memory budget. The hint does not guarantee `maxCount`
     *   buffers will actually be retained.
     *
     * **Scope**: hints apply to this allocator instance only; they
     * are not propagated retroactively to child allocators already
     * produced by [createChild]. Callers should invoke this on the
     * per-EventLoop allocator instance (typically via
     * `ctx.allocator`) rather than the parent engine-wide allocator.
     *
     * Typical callers:
     * - Engine: `hintSizeClass(READ_BUFFER_SIZE, 16)` at bind time —
     *   signals the recv-buffer size class as hot.
     * - TlsHandler: `hintSizeClass(TLS_PLAINTEXT_BUF_SIZE, 4)` at
     *   pipeline setup — signals the plaintext-record size class as
     *   hot for the duration of the TLS session.
     */
    fun hintSizeClass(byteSize: Int, maxCount: Int) {}

    /**
     * Creates a child allocator instance scoped to the caller's
     * lifecycle.
     *
     * The caller **owns** the returned allocator and is responsible
     * for invoking [close] on it — pool-based parents track every
     * child they produce and cascade-close them in [close], so a
     * parent's close releases every still-open child; calling
     * [close] on the child directly is the more common pattern
     * (engines close their per-EventLoop / per-engine children when
     * the EventLoop or engine tears down).
     *
     * Stateless allocators (e.g. [DefaultAllocator]) return `this`,
     * so they remain reusable. Pool-based allocators return a fresh
     * instance with its own freelist and chunk arena — a per-thread
     * pool when the engine is thread-pinned (epoll / kqueue / nio /
     * io_uring), or a single engine-wide pool when the engine has no
     * per-thread split (NwEngine, NodeEngine).
     *
     * See [createUntrackedChild] for children whose lifecycle the parent
     * must **not** track (one allocator per accepted connection, etc.).
     */
    fun createChild(): BufferAllocator = this

    /**
     * Creates a child allocator whose lifecycle is **fully owned by the
     * caller** and is **not tracked** by this parent for cascade-close.
     *
     * Identical to [createChild] except the parent does not retain the
     * returned child: this parent's [close] will not close it, and the
     * caller **must** [close] it exactly once itself.
     *
     * **What comes back may be this allocator.** The default chain ends at
     * [createChild]'s `this`, so a stateless implementation answers with itself
     * — and then "close it exactly once" closes the allocator the caller was
     * given. Nothing in this interface distinguishes a new child from the
     * receiver, and identity does not settle it either: a wrapper forwards its
     * delegate's answer outward, so what comes back is neither the receiver nor
     * anything new. A caller that must not close somebody else's allocator
     * should be handed one that makes real children rather than try to tell.
     *
     * Use this for children with an independent, churning population the
     * parent cannot bound — e.g. one allocator per accepted connection,
     * closed when that connection tears down. Registering such children
     * with [createChild] would (a) let the parent's teardown fan-out close
     * them a second time, racing the connection's own close, and (b) grow
     * the tracking set without bound. Per-EventLoop children (fixed count,
     * each closed once by its owning EventLoop) should use [createChild].
     *
     * Stateless allocators return `this` via the [createChild] default, so
     * they remain reusable regardless of tracking.
     */
    fun createUntrackedChild(): BufferAllocator = createChild()

    /**
     * Installs the [ConfinementToken] this allocator classifies releases against —
     * same-owner takes the freelist fast path, cross-context routes to the owner.
     * Default: no-op — allocators with no cross-thread routing ignore it. A
     * thread-pinned allocator defaults to a thread-id token; a serial-confined
     * engine (e.g. NWConnection on GCD, which serialises allocate / release on one
     * queue but migrates across worker pthreads) installs a queue-identity token on
     * each [createChild], so same-queue releases take the fast path while genuinely
     * off-queue releases are still routed correctly. Wrapper allocators forward to
     * their delegate so the token survives wrapping. Call it before the first
     * allocate on the returned child.
     */
    fun installConfinement(token: ConfinementToken) {}

    /**
     * Pull-shape snapshot view of the allocator's state for telemetry
     * adapters (OpenTelemetry `ObservableUpDownCounter` callbacks,
     * Micrometer gauges, Prometheus scrape endpoints). Complements the
     * push hook [BufferAllocatorStatsCounter] — push for hot-path
     * cumulative counters, pull for current state at collection time.
     *
     * Default returns [NoOpAllocatorStats] (all counters zero, class
     * count zero) so stateless allocators ([DefaultAllocator] etc.)
     * inherit a working no-op. Pool-based allocators override to expose
     * real counters and per-class detail.
     *
     * Snapshot cadence is collection cycle (~15s in OT default); not a
     * hot-path call.
     */
    fun stats(): AllocatorStats = NoOpAllocatorStats

    /**
     * Releases the allocator's pooled buffers and any platform resources
     * (`pthread_mutex_t`, file descriptors, etc.) it holds. Engines call
     * this on teardown after they have stopped their EventLoop threads,
     * so the close path is single-threaded with no in-flight [allocate]
     * calls.
     *
     * **Buffers in use at close time stay alive.** Pool-managed
     * `IoBuf`s whose refCount is still positive (e.g. an in-flight write
     * not yet flushed) are not touched; their `release()` later runs the
     * "allocator closed" branch and frees the backing directly instead of
     * returning to the pool. The implementation may log a warning naming
     * the in-use count.
     *
     * Implementations must be **idempotent** — a second [close] is a
     * no-op. Calling [allocate] or [createChild] after [close]
     * throws [IllegalStateException]. The default body is a no-op,
     * covering pool-less allocators ([DefaultAllocator], JS).
     *
     * Not declared on [AutoCloseable] — adding that supertype changes
     * the Kotlin/JS name mangling of every interface method that takes
     * a [BufferAllocator] (`Encoder.newSession`, etc.) and de-syncs
     * implementation / call-site bytecode across modules. Callers that
     * want `use { }` should wrap the allocator in a thin `AutoCloseable`
     * adapter at the call site.
     */
    fun close() {}
}

/**
 * Convenience alias for [BufferAllocator.wrapBytes].
 *
 * Kept for backward compatibility with callers that use the extension
 * function form. New code should call [BufferAllocator.wrapBytes] directly.
 */
fun BufferAllocator.tryWrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? =
    wrapBytes(bytes, offset, length)

/**
 * Allocates a fresh [IoBuf] on every call.
 *
 * Works on all targets and all engines. Intended for tests and
 * environments where pooling is unnecessary. Not recommended for
 * production workloads due to per-allocation overhead.
 *
 * Stateless: [createChild] returns `this`.
 */
object DefaultAllocator : BufferAllocator {
    @Suppress("IoBufLeak") // Allocator returns ownership to caller
    override fun allocate(capacity: Int): IoBuf = createDefaultIoBuf(capacity)

    override fun wrapBytes(bytes: ByteArray, offset: Int, length: Int): IoBuf? = null

    override fun slice(source: IoBuf, offset: Int, length: Int): IoBuf =
        sliceDefaultIoBuf(source, offset, length)
}

/**
 * Returns the recommended default [BufferAllocator] for the current platform.
 *
 * - **Native** (Linux/macOS): [SlabAllocator] — per-EventLoop nativeHeap pool
 * - **JVM**: [PooledDirectAllocator] — per-EventLoop DirectByteBuffer pool
 * - **JS**: [DefaultAllocator] — V8 GC manages Int8Array; pooling is unnecessary
 *
 * The optional [statsCounter] is wired into platforms whose default allocator
 * is a [PooledAllocator] (Native + JVM); other platforms ignore it. Pass
 * [NoOpStatsCounter] (default) for normal runs; pass a real implementation —
 * for example a [PoolMissProfile] (which implements
 * [BufferAllocatorStatsCounter]) for `--profile-alloc` instrumentation, or an
 * OpenTelemetry adapter — so every dispatch records its path
 * (hit / miss / empty / huge) and its size tier.
 */
expect fun defaultAllocator(statsCounter: BufferAllocatorStatsCounter = NoOpStatsCounter): BufferAllocator

/**
 * Returns the default [BufferAllocator] with both a hot-path [statsCounter] and
 * an identity-bearing [lifecycleListener] wired.
 *
 * Use this overload when a measurement needs the [IoBuf] reference on
 * allocate / release — for example a [CrossThreadReleaseProfile] for
 * `--profile-xthread`. The listener channel is wired only on platforms whose
 * default is a [PooledAllocator] (Native + JVM); JS ignores it. Pass
 * [NoOpStatsCounter] for [statsCounter] if only the lifecycle listener is needed.
 */
expect fun defaultAllocator(
    statsCounter: BufferAllocatorStatsCounter,
    lifecycleListener: BufferAllocatorLifecycleListener,
): BufferAllocator
