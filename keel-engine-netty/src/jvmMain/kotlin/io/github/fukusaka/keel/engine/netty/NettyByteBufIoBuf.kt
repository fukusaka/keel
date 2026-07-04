package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.BufferAllocatorLifecycleListener
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.NioByteBufferBacking
import io.github.fukusaka.keel.buf.NoOpLifecycleListener
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.netty.buffer.ByteBuf
import io.netty.util.IllegalReferenceCountException
import java.nio.ByteBuffer

/**
 * [IoBuf] implementation backed directly by a Netty [ByteBuf]
 * (engine-direct — does not extend
 * [io.github.fukusaka.keel.buf.AbstractIoBuf]).
 *
 * Used by two paths on the Netty engine:
 *
 * - **Allocator path** (write side): [NettyByteBufAllocator] hands out
 *   a fresh empty [ByteBuf] wrapped as `NettyByteBufIoBuf(byteBuf)`
 *   (default constructor: `baseIndex = 0`, `initialWriterIndex = 0`).
 *   The keel pipeline writes bytes straight into the pooled `ByteBuf`;
 *   the flush path in [NettyIoTransport] detects the wrapper and hands
 *   the underlying `ByteBuf` to `nettyChannel.writeAndFlush` without
 *   the `Unpooled.wrappedBuffer` wrapper step that a generic
 *   `DirectIoBuf` requires.
 *
 * - **Inbound path** (read side): [NettyIoTransport]'s `channelRead`
 *   handler wraps the incoming `ByteBuf` via [wrapInbound] (zero-copy,
 *   ownership transferred to the wrapper). [baseOffset] biases keel
 *   indices to the `ByteBuf`'s current `readerIndex` so keel sees the
 *   readable region as `[0, readableBytes)`. This replaces a generic
 *   `DirectIoBuf.wrapExternal` + ad-hoc
 *   [io.github.fukusaka.keel.buf.IoBufOwner] closure path with a
 *   single `NettyByteBufIoBuf` allocation per receive.
 *
 * **Refcount**: delegated 1:1 to the underlying Netty [ByteBuf]'s atomic
 * `refCnt`. Every keel-side [retain] / [release] is a direct pass-through
 * to [ByteBuf.retain] / [ByteBuf.release] — the wrapper carries no
 * separate counter and no non-atomic mirror. This makes the lifecycle
 * thread-safe through to the underlying ByteBuf's atomic CAS, matching
 * the [IoBuf] contract that lifecycle (retain / release / close) is
 * thread-safe across every keel buffer implementation. Explicit extra
 * holds on the `ByteBuf` (e.g. `retainedSlice` during flush) compose
 * naturally: every reserve, regardless of where it originated,
 * contributes to the same `refCnt` and the buffer goes back to the
 * Netty pool when `refCnt` reaches zero. [IllegalReferenceCountException]
 * from a retain / release on an already-freed `ByteBuf` is rewrapped as
 * [IllegalStateException] to honour the [IoBuf] contract's exception
 * type.
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
 * with no leak window. `close()` is idempotent: a second call lands on
 * an already-released `ByteBuf` and the resulting
 * [IllegalReferenceCountException] is swallowed.
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
 *                   for the write-side path, [wrapInbound] for the
 *                   inbound zero-copy path) so the listener observes
 *                   fully-constructed buffers only. Defaults to
 *                   [NoOpLifecycleListener] for tests / paths that do
 *                   not configure a listener; engine-direct lifecycle
 *                   wiring (item 12 B2.5) flows the user-passed
 *                   `config.allocator.lifecycleListener` through here.
 */
internal class NettyByteBufIoBuf(
    internal val byteBuf: ByteBuf,
    private val baseOffset: Int = 0,
    initialWriterIndex: Int = 0,
    private val lifecycleListener: BufferAllocatorLifecycleListener = NoOpLifecycleListener,
) : IoBuf, NioByteBufferBacking {

    override val capacity: Int = byteBuf.capacity() - baseOffset

    override var readerIndex: Int = 0
    override var writerIndex: Int = initialWriterIndex

    override val readableBytes: Int get() = writerIndex - readerIndex
    override val writableBytes: Int get() = capacity - writerIndex

    /**
     * Writable [ByteBuffer] view over `[baseOffset, baseOffset + capacity)`
     * in the underlying [ByteBuf], i.e. the same keel-visible window as
     * indices `[0, capacity)` exposed by this wrapper.
     *
     * Cached once at construction to avoid per-record allocation on the
     * TLS hot path
     * ([io.github.fukusaka.keel.tls.jsse.JsseTlsCodec] calls this on
     * every [javax.net.ssl.SSLEngine.wrap] / [javax.net.ssl.SSLEngine.unwrap]).
     * The view shares the same off-heap memory as the underlying
     * [ByteBuf], so bytes written by SSLEngine are immediately visible
     * via [byteBuf] accessor methods used by the flush path in
     * [NettyIoTransport]. Callers must set [ByteBuffer.position] and
     * [ByteBuffer.limit] before each use.
     *
     * **Capacity**: fixed to `[0, capacity)` at construction.
     * The underlying [ByteBuf] is never resized after allocation, so
     * the range remains valid for this object's lifetime.
     *
     * **Lifetime**: valid only while this [IoBuf]'s keel refcount is
     * greater than zero. Once [release] drops the refcount to zero, the
     * underlying [ByteBuf] is returned to the Netty pool and the
     * off-heap memory may be reused. Accessing this [ByteBuffer] after
     * [release] is a use-after-free.
     */
    @UnsafeIoBufApi
    override val unsafeNioByteBuffer: ByteBuffer = byteBuf.nioBuffer(baseOffset, capacity)

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
        if (freed) lifecycleListener.onReleased(this)
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
        if (freed) lifecycleListener.onReleased(this)
    }

    /**
     * Zero-copy slice of keel indices `[offset, offset + length)`: a Netty
     * `retainedSlice` sharing this buffer's off-heap memory with an added
     * reserve on the shared `refCnt`. The returned buffer can outlive this one
     * (the held-buffer path holds body slices after the inbound buffer is
     * consumed) and returns to the pool when its own refcount reaches zero — no
     * `ByteArray` copy, unlike the allocator's foreign-buffer slice fallback.
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
    }
}
