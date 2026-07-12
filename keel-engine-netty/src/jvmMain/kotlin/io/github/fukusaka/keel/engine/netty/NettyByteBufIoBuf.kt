package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.BufferAllocatorLifecycleListener
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.NioByteBufferBacking
import io.github.fukusaka.keel.buf.NoOpLifecycleListener
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.netty.buffer.ByteBuf
import io.netty.buffer.Unpooled
import io.netty.util.IllegalReferenceCountException
import io.netty.util.Recycler
import java.nio.ByteBuffer

/**
 * [IoBuf] implementation backed directly by a Netty [ByteBuf]
 * (engine-direct — does not extend
 * [io.github.fukusaka.keel.buf.AbstractIoBuf]).
 *
 * Used by two paths on the Netty engine:
 *
 * - **Allocator path** (write side): [NettyByteBufAllocator] hands out
 *   a fresh empty [ByteBuf] wrapped via [borrow] (pooled — see "Wrapper
 *   pooling" below). The keel pipeline writes bytes straight into the
 *   pooled `ByteBuf`; the flush path in [NettyIoTransport] detects the
 *   wrapper and hands the underlying `ByteBuf` to
 *   `nettyChannel.writeAndFlush` without the `Unpooled.wrappedBuffer`
 *   wrapper step that a generic `DirectIoBuf` requires.
 *
 * - **Inbound path** (read side): [NettyIoTransport]'s `channelRead`
 *   handler wraps the incoming `ByteBuf` via [borrowInbound] (zero-copy,
 *   pooled, ownership transferred to the wrapper). [baseOffset] biases
 *   keel indices to the `ByteBuf`'s current `readerIndex` so keel sees
 *   the readable region as `[0, readableBytes)`. This replaces a
 *   generic `DirectIoBuf.wrapExternal` + ad-hoc
 *   [io.github.fukusaka.keel.buf.IoBufOwner] closure path. [wrapInbound]
 *   (unpooled) remains for callers outside the channel's own `EventLoop`
 *   thread, where [RECYCLER] never pools anyway — see [borrow]'s KDoc.
 *
 * **Wrapper pooling**: [borrow] / [borrowInbound] recycle the wrapper
 * *object* itself (not just the `ByteBuf` memory Netty already pools)
 * via [RECYCLER], [rebind]ing a released instance onto the next
 * caller's `byteBuf` instead of constructing fresh every call. See
 * [RECYCLER]'s KDoc for the thread requirement and [rebind]'s KDoc for
 * the caveat this reuse carries: unlike an un-recycled wrapper, holding
 * a reference past [release] / [close] is no longer guaranteed to fail
 * loudly on next use — see "Refcount" below.
 *
 * **Refcount**: delegated 1:1 to the underlying Netty [ByteBuf]'s atomic
 * `refCnt`. Every keel-side [retain] / [release] is a direct pass-through
 * to [ByteBuf.retain] / [ByteBuf.release] — the wrapper carries no
 * separate counter and no non-atomic mirror. This makes the lifecycle
 * thread-safe through to the underlying ByteBuf's atomic CAS *within a
 * single lease* — concurrent [retain] / [release] / [close] calls that
 * all still legitimately hold this lease observe `refCnt`'s atomic CAS
 * and either succeed or throw [IllegalStateException], matching the
 * [IoBuf] contract. Explicit extra holds on the `ByteBuf` (e.g.
 * `retainedSlice` during flush) compose naturally: every reserve,
 * regardless of where it originated, contributes to the same `refCnt`
 * and the buffer goes back to the Netty pool when `refCnt` reaches zero.
 * [IllegalReferenceCountException] from a retain / release on an
 * already-freed `ByteBuf` is rewrapped as [IllegalStateException] to
 * honour the [IoBuf] contract's exception type.
 *
 * **Cross-lease caveat (pooled wrappers only)**: this guarantee does
 * *not* extend across a [release] / [close] that recycles the wrapper
 * (see [recycleHandle]) and a later [rebind] onto an unrelated `byteBuf`.
 * A caller that (in violation of [IoBuf]'s ownership contract — "the
 * caller must not touch it afterwards") retains a `NettyByteBufIoBuf`
 * reference past its own [release] / [close] call and later invokes
 * [retain] / [release] / content access on it again will, if the
 * instance has since been recycled and rebound, silently observe or
 * mutate a *different, currently-live* buffer instead of hitting
 * `refCnt == 0` and throwing. This is the same identity-reuse caveat
 * every pooled keel buffer carries — see
 * [io.github.fukusaka.keel.buf.AbstractIoBuf.resetForReuse]'s
 * `refCount` reset for the equivalent case in keel's own
 * `PooledAllocator`-backed buffers — not a new class of risk introduced
 * by wrapper pooling. Defending against it would require a per-lease
 * generation token threaded through every caller, which no `IoBuf`
 * implementation in keel does today; the actual defense is the
 * ownership contract itself, not a runtime guard.
 *
 * **`close()` semantics — delegates to `byteBuf.release()`**, **not** the
 * `AbstractIoBuf` intentional-leak shape from PR #351. PR #351's leak
 * exists because `AbstractIoBuf` close-time runs `freeBacking()` on
 * own-memory backings (`nativeHeap.free` / chunk-arena `returnChunkRun`),
 * and engine shutdown can land that on a pool that is itself going away.
 * `NettyByteBufIoBuf` sits over a Netty allocator's pool — the adaptive
 * `ch.alloc()` default on the Netty engine, or `PooledByteBufAllocator`
 * via [nettyByteBufAllocator] — and those pools are process-lifetime
 * (Netty's allocators expose no `close()` API), so returning the
 * wrapper's reserve to the pool at close time is always safe and is the
 * right cleanup. Folding
 * close into `byteBuf.release()` also eliminates the TOCTOU window that
 * a separate `closed` flag would carry (the flag check and
 * `byteBuf.retain()` cannot be fused into a single CAS across two
 * independent atomic primitives), so every retain / release / close runs
 * exclusively through `byteBuf.refCnt`'s atomic CAS — fully thread-safe
 * with no leak window, within a single lease. `close()` is idempotent
 * *within that lease*: a second call before this wrapper is recycled
 * lands on the same already-released `ByteBuf` and the resulting
 * [IllegalReferenceCountException] is swallowed. See the "Cross-lease
 * caveat" above for a pooled wrapper's behaviour once it has been
 * recycled and [rebind] onto a new lease.
 *
 * @param byteBuf    The Netty [ByteBuf] backing this buffer.
 * @param baseOffset Index in [byteBuf] that corresponds to keel-index 0.
 *                   Zero for the allocator path (fresh buf, fill from 0);
 *                   `byteBuf.readerIndex()` for the inbound path (wrap
 *                   an already-filled buf so keel sees the readable
 *                   region as `[0, readableBytes)`).
 * @param initialWriterIndex Initial value for [writerIndex]. Zero for
 *                   the allocator path; `byteBuf.readableBytes()` for
 *                   the inbound path.
 * @param lifecycleListener [BufferAllocatorLifecycleListener] notified
 *                   from [release] / [close] when the final reserve
 *                   on this wrapper drops `byteBuf.refCnt` to zero. The
 *                   matching [BufferAllocatorLifecycleListener.onAllocated]
 *                   call is fired by the factory ([NettyByteBufAllocator]
 *                   for the write-side path, [borrowInbound] /
 *                   [wrapInbound] for the inbound zero-copy path) so the
 *                   listener observes
 *                   fully-constructed buffers only. Defaults to
 *                   [NoOpLifecycleListener] for tests / paths that do
 *                   not configure a listener; engine-direct lifecycle
 *                   wiring (item 12 B2.5) flows the user-passed
 *                   `config.allocator.lifecycleListener` through here.
 * @param recycleHandle When non-null, this instance was borrowed from
 *                   [RECYCLER] ([borrow]) and is returned to the pool
 *                   from [release] / [close] instead of being left for
 *                   GC. `null` for wrappers built directly by the
 *                   primary constructor ([retainedSlice], tests) — those
 *                   are never recycled since they may outlive the
 *                   instance that produced them or are one-off.
 */
internal class NettyByteBufIoBuf(
    internal var byteBuf: ByteBuf,
    private var baseOffset: Int = 0,
    initialWriterIndex: Int = 0,
    private var lifecycleListener: BufferAllocatorLifecycleListener = NoOpLifecycleListener,
    private val recycleHandle: Recycler.Handle<NettyByteBufIoBuf>? = null,
) : IoBuf, NioByteBufferBacking {

    override var capacity: Int = 0
        private set

    override var readerIndex: Int = 0
    override var writerIndex: Int = 0

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    /**
     * Writable [ByteBuffer] view over `[baseOffset, baseOffset + capacity)`
     * in the underlying [ByteBuf], i.e. the same keel-visible window as
     * indices `[0, capacity)` exposed by this wrapper.
     *
     * Cached once per lease (construction, or [rebind] for a pooled
     * wrapper — see [borrow]) to avoid per-record allocation on the TLS
     * hot path ([io.github.fukusaka.keel.tls.jsse.JsseTlsCodec] calls
     * this on every [javax.net.ssl.SSLEngine.wrap] /
     * [javax.net.ssl.SSLEngine.unwrap]). The view shares the same
     * off-heap memory as the underlying [ByteBuf], so bytes written by
     * SSLEngine are immediately visible via [byteBuf] accessor methods
     * used by the flush path in [NettyIoTransport]. Callers must set
     * [ByteBuffer.position] and [ByteBuffer.limit] before each use.
     *
     * **Capacity**: fixed to `[0, capacity)` for the current lease.
     * The underlying [ByteBuf] is never resized within a lease, so the
     * range remains valid for as long as this lease is live — but
     * [rebind] replaces both [capacity] and this view wholesale on the
     * next lease of a pooled wrapper.
     *
     * **Lifetime**: valid only while this [IoBuf]'s keel refcount is
     * greater than zero *for the current lease*. Once [release] drops
     * the refcount to zero, the underlying [ByteBuf] is returned to the
     * Netty pool and the off-heap memory may be reused. Accessing this
     * [ByteBuffer] after [release] is a use-after-free — and, for a
     * pooled wrapper, may silently return a view onto a *different*,
     * currently-live lease's memory rather than failing — see the class
     * KDoc's "Cross-lease caveat".
     */
    @UnsafeIoBufApi
    override lateinit var unsafeNioByteBuffer: ByteBuffer
        private set

    init {
        // Delegates the primary construction's initial lease to rebind()
        // (same formula [rebind] uses for a pooled wrapper's later
        // leases) so there is exactly one place that derives capacity /
        // unsafeNioByteBuffer / indices from byteBuf — see [rebind]'s
        // KDoc.
        @OptIn(UnsafeIoBufApi::class)
        rebind(byteBuf, baseOffset, initialWriterIndex, lifecycleListener)
    }

    /**
     * (Re)binds this wrapper to [byteBuf] / [baseOffset] / [writerIndex] /
     * [lifecycleListener] — the single source of truth for both the
     * primary constructor's initial lease (called from `init`) and a
     * pooled wrapper's later leases (called from [borrow] on an instance
     * obtained from [RECYCLER]). For the pooled case this is only ever
     * called on an instance no caller can still observe (recycling
     * happens strictly after [release] / [close] drops `refCnt` to
     * zero).
     */
    @OptIn(UnsafeIoBufApi::class)
    private fun rebind(
        byteBuf: ByteBuf,
        baseOffset: Int,
        initialWriterIndex: Int,
        lifecycleListener: BufferAllocatorLifecycleListener,
    ) {
        this.byteBuf = byteBuf
        this.baseOffset = baseOffset
        this.capacity = byteBuf.capacity() - baseOffset
        this.readerIndex = 0
        this.writerIndex = initialWriterIndex
        this.lifecycleListener = lifecycleListener
        this.unsafeNioByteBuffer = byteBuf.nioBuffer(baseOffset, capacity)
    }

    override fun writeByte(value: Byte) {
        byteBuf.setByte(baseOffset + writerIndex, value.toInt())
        writerIndex++
    }

    override fun writeByteArray(src: ByteArray, offset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        byteBuf.setBytes(baseOffset + writerIndex, src, offset, length)
        writerIndex += length
    }

    override fun writeAscii(src: String, srcOffset: Int, length: Int) {
        require(length <= writableBytes) { "length $length exceeds writableBytes $writableBytes" }
        var i = 0
        while (i < length) {
            byteBuf.setByte(baseOffset + writerIndex + i, src[srcOffset + i].code)
            i++
        }
        writerIndex += length
    }

    override fun copyTo(dest: IoBuf, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        require(length <= dest.writableBytes) { "length $length exceeds dest.writableBytes ${dest.writableBytes}" }
        if (length == 0) return
        val tmp = ByteArray(length)
        byteBuf.getBytes(baseOffset + readerIndex, tmp, 0, length)
        dest.writeByteArray(tmp, 0, length)
        readerIndex += length
    }

    override fun readByteArray(dest: ByteArray, offset: Int, length: Int) {
        require(length <= readableBytes) { "length $length exceeds readableBytes $readableBytes" }
        if (length == 0) return
        byteBuf.getBytes(baseOffset + readerIndex, dest, offset, length)
        readerIndex += length
    }

    override fun readByte(): Byte = byteBuf.getByte(baseOffset + readerIndex++)

    override fun getByte(index: Int): Byte = byteBuf.getByte(baseOffset + index)

    override fun clear() {
        readerIndex = 0
        writerIndex = 0
    }

    override fun retain(): IoBuf {
        try {
            byteBuf.retain()
        } catch (e: IllegalReferenceCountException) {
            throw IllegalStateException("Cannot retain a released buffer", e)
        }
        return this
    }

    override fun release(): Boolean {
        val freed = try {
            // Delegate directly to the atomic refCnt. Returns true when the
            // underlying ByteBuf went back to the Netty pool (refCnt 1 → 0).
            byteBuf.release()
        } catch (e: IllegalReferenceCountException) {
            throw IllegalStateException("Buffer already released", e)
        }
        if (freed) {
            lifecycleListener.onReleased(this)
            recycleHandle?.recycle(this)
        }
        return freed
    }

    override fun close() {
        // Delegates to byteBuf.release() — see class KDoc's "close()
        // semantics" section for why this wrapper does NOT follow
        // AbstractIoBuf's PR #351 intentional-leak shape (in short: Netty's
        // pool is process-lifetime so returning the reserve is always safe,
        // and delegating eliminates the TOCTOU window a separate flag
        // would carry). Idempotent: a second call lands on an
        // already-released ByteBuf and the resulting
        // IllegalReferenceCountException is swallowed.
        val freed = try {
            byteBuf.release()
        } catch (e: IllegalReferenceCountException) {
            // Already released — IoBuf.close() is documented as idempotent.
            @Suppress("SwallowedException", "UnusedPrivateMember")
            val ignored = e
            false
        }
        if (freed) {
            lifecycleListener.onReleased(this)
            recycleHandle?.recycle(this)
        }
    }

    /**
     * Zero-copy slice of keel indices `[offset, offset + length)`: a Netty
     * `retainedSlice` sharing this buffer's off-heap memory with an added
     * reserve on the shared `refCnt`. The returned buffer can outlive this one
     * (the held-buffer path holds body slices after the inbound buffer is
     * consumed) and returns to the pool when its own refcount reaches zero — no
     * `ByteArray` copy, unlike the allocator's foreign-buffer slice fallback.
     *
     * **Not pooled**: the returned wrapper is built via the primary
     * constructor with no [recycleHandle], so it is never pushed to
     * [RECYCLER] on release — it may outlive the wrapper it was sliced
     * from, which [rebind] requires not happening for a pooled instance.
     * A consequence: an inbound wrapper borrowed via [borrowInbound] that
     * gets sliced (e.g. per-chunk HTTP body slicing) never itself reaches
     * `refCnt == 0` from its *own* [release] call — the slice's later
     * release is what actually frees the shared `byteBuf` — so
     * `recycleHandle.recycle` never fires for that borrow and the pooling
     * benefit is lost for sliced receives.
     */
    internal fun retainedSlice(
        offset: Int,
        length: Int,
        listener: BufferAllocatorLifecycleListener,
    ): NettyByteBufIoBuf {
        val sliced = byteBuf.retainedSlice(baseOffset + offset, length)
        val buf = NettyByteBufIoBuf(sliced, baseOffset = 0, initialWriterIndex = length, lifecycleListener = listener)
        listener.onAllocated(buf)
        return buf
    }

    companion object {
        /**
         * Wraps an already-populated inbound [ByteBuf] as an
         * engine-direct [NettyByteBufIoBuf] (the `channelRead`
         * zero-copy path). The keel-side view covers
         * `[readerIndex(), capacity())`, with [writerIndex] preset
         * to [ByteBuf.readableBytes].
         *
         * Ownership of the [ByteBuf] is transferred to the returned
         * wrapper — the pooled buffer is returned to Netty's arena
         * when the wrapper's keel refcount reaches zero.
         *
         * Fires [BufferAllocatorLifecycleListener.onAllocated] on the
         * supplied [lifecycleListener] for the returned wrapper so
         * engine-direct inbound buffers are observable through the
         * same channel as write-side allocations from
         * [NettyByteBufAllocator]. Defaults to [NoOpLifecycleListener]
         * for paths that do not configure a listener.
         */
        fun wrapInbound(
            byteBuf: ByteBuf,
            lifecycleListener: BufferAllocatorLifecycleListener = NoOpLifecycleListener,
        ): NettyByteBufIoBuf {
            val buf = NettyByteBufIoBuf(
                byteBuf,
                baseOffset = byteBuf.readerIndex(),
                initialWriterIndex = byteBuf.readableBytes(),
                lifecycleListener = lifecycleListener,
            )
            lifecycleListener.onAllocated(buf)
            return buf
        }

        /**
         * Thread-local-stack pool of wrapper objects, keyed by nothing but
         * type — every borrower gets whichever recycled instance (or a
         * fresh one backed by [Unpooled.EMPTY_BUFFER] until [rebind]
         * overwrites it) the calling thread's local pool currently holds.
         *
         * Safe to [borrow] and [Recycler.Handle.recycle] from different
         * threads: [Recycler]'s per-thread stack falls back to a
         * lock-free cross-thread return path when the recycling thread
         * isn't the borrowing thread (the same mechanism Netty uses for
         * its own pooled `ByteBuf` instances) — required here because
         * [NettyByteBufAllocator] (see its class KDoc) can be a single
         * instance shared across multiple `EventLoop` threads when wired
         * as a general-purpose [io.github.fukusaka.keel.buf.BufferAllocator],
         * not just the per-`EventLoop`-confined instance
         * [NettyEngine.allocatorFor] hands to [NettyIoTransport].
         *
         * **Only pools on `io.netty.util.concurrent.FastThreadLocalThread`**
         * (Recycler's own `get()` contract: on any other JVM thread it
         * unconditionally hands back a fresh, unpooled instance, no
         * matter how many borrow/release cycles run). Netty's
         * `EventLoopGroup` threads (`DefaultThreadFactory`) are always
         * `FastThreadLocalThread`, so [NettyIoTransport]'s `channelRead`
         * and [NettyByteBufAllocator.allocate] — both always invoked on
         * the channel's own `EventLoop` — get real pooling in
         * production. A caller on a foreign thread (e.g. a test running
         * on the JUnit runner thread) still gets correct behaviour, just
         * without the pooling benefit.
         */
        private val RECYCLER = object : Recycler<NettyByteBufIoBuf>() {
            override fun newObject(handle: Handle<NettyByteBufIoBuf>): NettyByteBufIoBuf =
                NettyByteBufIoBuf(Unpooled.EMPTY_BUFFER, recycleHandle = handle)
        }

        /**
         * Borrows a pooled [NettyByteBufIoBuf] from [RECYCLER] and
         * [rebind]s it to [byteBuf] / [baseOffset] / [initialWriterIndex] /
         * [lifecycleListener]. Fires
         * [BufferAllocatorLifecycleListener.onAllocated] on the returned
         * wrapper, matching [wrapInbound] / [NettyByteBufAllocator.allocate]'s
         * existing contract. The returned instance is recycled back to
         * [RECYCLER] from [release] / [close] once `byteBuf.refCnt` drops
         * to zero — see [recycleHandle].
         */
        internal fun borrow(
            byteBuf: ByteBuf,
            baseOffset: Int,
            initialWriterIndex: Int,
            lifecycleListener: BufferAllocatorLifecycleListener,
        ): NettyByteBufIoBuf {
            val buf = RECYCLER.get()
            buf.rebind(byteBuf, baseOffset, initialWriterIndex, lifecycleListener)
            lifecycleListener.onAllocated(buf)
            return buf
        }

        /**
         * [borrow] specialised for the `channelRead` zero-copy path: same
         * index derivation as [wrapInbound] ([baseOffset] =
         * `byteBuf.readerIndex()`, [writerIndex] = `byteBuf.readableBytes()`),
         * but returns a pooled wrapper instead of allocating a fresh one.
         */
        internal fun borrowInbound(
            byteBuf: ByteBuf,
            lifecycleListener: BufferAllocatorLifecycleListener = NoOpLifecycleListener,
        ): NettyByteBufIoBuf = borrow(
            byteBuf,
            baseOffset = byteBuf.readerIndex(),
            initialWriterIndex = byteBuf.readableBytes(),
            lifecycleListener = lifecycleListener,
        )
    }
}
