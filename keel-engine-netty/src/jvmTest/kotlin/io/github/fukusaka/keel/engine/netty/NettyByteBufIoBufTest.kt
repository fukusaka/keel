package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.NoOpLifecycleListener
import io.netty.buffer.ByteBufAllocator
import io.netty.util.concurrent.FastThreadLocalThread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NettyByteBufIoBufTest {

    private fun newBuf(cap: Int = 16): NettyByteBufIoBuf {
        val byteBuf = ByteBufAllocator.DEFAULT.directBuffer(cap, cap)
        return NettyByteBufIoBuf(byteBuf)
    }

    @Test
    fun `initial state`() {
        val buf = newBuf(16)
        assertEquals(16, buf.capacity)
        assertEquals(0, buf.readerIndex)
        assertEquals(0, buf.writerIndex)
        assertEquals(0, buf.readableBytes)
        assertEquals(16, buf.writableBytes)
        buf.release()
    }

    @Test
    fun `writeByte advances writerIndex and persists`() {
        val buf = newBuf()
        buf.writeByte(0x41)
        buf.writeByte(0x42)
        assertEquals(2, buf.writerIndex)
        assertEquals(0x41.toByte(), buf.byteBuf.getByte(0))
        assertEquals(0x42.toByte(), buf.byteBuf.getByte(1))
        buf.release()
    }

    @Test
    fun `writeByteArray bulk copies into underlying ByteBuf`() {
        val buf = newBuf()
        val src = byteArrayOf(1, 2, 3, 4, 5)
        buf.writeByteArray(src, 0, 5)
        assertEquals(5, buf.writerIndex)
        val out = ByteArray(5)
        buf.byteBuf.getBytes(0, out, 0, 5)
        assertContentEquals(src, out)
        buf.release()
    }

    @Test
    fun `writeAscii encodes single-byte characters`() {
        val buf = newBuf()
        buf.writeAscii("Hello", 0, 5)
        assertEquals(5, buf.writerIndex)
        val out = ByteArray(5)
        buf.byteBuf.getBytes(0, out, 0, 5)
        assertContentEquals("Hello".toByteArray(Charsets.US_ASCII), out)
        buf.release()
    }

    @Test
    fun `writeAscii with srcOffset`() {
        val buf = newBuf()
        buf.writeAscii("HelloWorld", 5, 5)
        val out = ByteArray(5)
        buf.byteBuf.getBytes(0, out, 0, 5)
        assertContentEquals("World".toByteArray(Charsets.US_ASCII), out)
        buf.release()
    }

    @Test
    fun `writeByteArray rejects oversize`() {
        val buf = newBuf(8)
        assertFailsWith<IllegalArgumentException> {
            buf.writeByteArray(ByteArray(16), 0, 16)
        }
        buf.release()
    }

    @Test
    fun `readByte advances readerIndex`() {
        val buf = newBuf()
        buf.writeByte(0x30)
        buf.writeByte(0x31)
        buf.writeByte(0x32)
        assertEquals(0x30.toByte(), buf.readByte())
        assertEquals(0x31.toByte(), buf.readByte())
        assertEquals(2, buf.readerIndex)
        assertEquals(1, buf.readableBytes)
        buf.release()
    }

    @Test
    fun `readByteArray bulk reads and advances`() {
        val buf = newBuf()
        buf.writeByteArray(byteArrayOf(10, 20, 30, 40), 0, 4)
        val dest = ByteArray(4)
        buf.readByteArray(dest, 0, 4)
        assertContentEquals(byteArrayOf(10, 20, 30, 40), dest)
        assertEquals(4, buf.readerIndex)
        buf.release()
    }

    @Test
    fun `getByte is absolute and does not move indices`() {
        val buf = newBuf()
        buf.writeByte(0xAA.toByte())
        buf.writeByte(0xBB.toByte())
        assertEquals(0xAA.toByte(), buf.getByte(0))
        assertEquals(0xBB.toByte(), buf.getByte(1))
        assertEquals(0, buf.readerIndex)
        buf.release()
    }

    @Test
    fun `clear resets indices`() {
        val buf = newBuf()
        buf.writeByte(1)
        buf.writeByte(2)
        buf.readByte()
        buf.clear()
        assertEquals(0, buf.readerIndex)
        assertEquals(0, buf.writerIndex)
        buf.release()
    }

    @Test
    fun `retain increments refcount, only release to zero triggers native release`() {
        val buf = newBuf()
        val nativeRef = buf.byteBuf
        assertEquals(1, nativeRef.refCnt())

        buf.retain()
        buf.retain()
        assertFalse(buf.release()) // 3 -> 2
        assertFalse(buf.release()) // 2 -> 1
        assertEquals(1, nativeRef.refCnt(), "native not released yet")
        assertTrue(buf.release()) // 1 -> 0
        assertEquals(0, nativeRef.refCnt())
    }

    @Test
    fun `release after zero throws`() {
        val buf = newBuf()
        buf.release()
        assertFailsWith<IllegalStateException> { buf.release() }
    }

    @Test
    fun `retain after release throws`() {
        val buf = newBuf()
        buf.release()
        assertFailsWith<IllegalStateException> { buf.retain() }
    }

    @Test
    fun `close delegates to byteBuf release and is idempotent`() {
        val buf = newBuf()
        val nativeRef = buf.byteBuf
        // close() decrements byteBuf.refCnt — Netty's pool is process-lifetime
        // so returning the reserve at close-time is always safe (and is the
        // right cleanup), unlike AbstractIoBuf's PR #351 intentional-leak
        // shape which exists for pool-tied own-memory backings.
        buf.close()
        assertEquals(0, nativeRef.refCnt(), "close must drop the native refcount via byteBuf.release()")
        // Idempotent: a second close on an already-released byteBuf is a
        // no-op. The IllegalReferenceCountException is swallowed per the
        // documented IoBuf.close() contract.
        buf.close()
        assertEquals(0, nativeRef.refCnt())
        // retain / release after close hit the underlying released byteBuf
        // and are translated to IllegalStateException.
        assertFailsWith<IllegalStateException> { buf.retain() }
        assertFailsWith<IllegalStateException> { buf.release() }
    }

    @Test
    fun `copyTo copies between NettyByteBufIoBuf instances`() {
        val src = newBuf()
        src.writeByteArray(byteArrayOf(1, 2, 3, 4), 0, 4)
        val dest = newBuf()
        src.copyTo(dest, 4)
        assertEquals(4, src.readerIndex)
        assertEquals(4, dest.writerIndex)
        val out = ByteArray(4)
        dest.readByteArray(out, 0, 4)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), out)
        src.release()
        dest.release()
    }

    @Test
    fun `release at refcount zero releases backing ByteBuf`() {
        val buf = newBuf()
        val nativeRef = buf.byteBuf
        assertEquals(1, nativeRef.refCnt())
        // release() at refcount zero is the single path that decrements the native ref.
        assertTrue(buf.release())
        assertEquals(0, nativeRef.refCnt())
    }

    // --- wrapInbound (engine-direct channelRead path) ---

    @Test
    fun `wrapInbound exposes readable region starting at keel-index zero`() {
        val byteBuf = ByteBufAllocator.DEFAULT.directBuffer(32, 32)
        // Simulate an inbound ByteBuf where data starts at readerIndex 4
        // (e.g. after Netty's decoder discarded a header). Pad to make
        // the offset visible in the bias arithmetic.
        byteBuf.writeBytes(ByteArray(4) { 0xFF.toByte() }) // garbage prefix
        byteBuf.writeBytes(byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50))
        byteBuf.readerIndex(4) // skip the garbage prefix

        val buf = NettyByteBufIoBuf.wrapInbound(byteBuf)
        assertEquals(5, buf.readableBytes, "keel sees 5 readable bytes (post-readerIndex)")
        assertEquals(0, buf.readerIndex)
        assertEquals(5, buf.writerIndex)
        assertEquals(28, buf.capacity, "capacity = byteBuf.capacity - readerIndex")

        // Absolute getByte through bias offset
        assertEquals(0x10.toByte(), buf.getByte(0))
        assertEquals(0x50.toByte(), buf.getByte(4))

        // Bulk read advances reader and reads through bias offset
        val out = ByteArray(5)
        buf.readByteArray(out, 0, 5)
        assertContentEquals(byteArrayOf(0x10, 0x20, 0x30, 0x40, 0x50), out)
        assertEquals(5, buf.readerIndex)

        buf.release()
        assertEquals(0, byteBuf.refCnt(), "release transfers ownership and frees the ByteBuf")
    }

    @Test
    fun `wrapInbound at readerIndex zero behaves like default constructor for read indices`() {
        val byteBuf = ByteBufAllocator.DEFAULT.directBuffer(16, 16)
        byteBuf.writeBytes(byteArrayOf(1, 2, 3))
        val buf = NettyByteBufIoBuf.wrapInbound(byteBuf)
        assertEquals(0, buf.readerIndex)
        assertEquals(3, buf.writerIndex)
        assertEquals(16, buf.capacity)
        assertEquals(1.toByte(), buf.readByte())
        assertEquals(2.toByte(), buf.readByte())
        assertEquals(3.toByte(), buf.readByte())
        buf.release()
    }

    // --- borrow / borrowInbound (pooled wrapper path) ---

    @Test
    fun `borrowInbound derives indices identically to wrapInbound`() {
        val byteBuf = ByteBufAllocator.DEFAULT.directBuffer(16, 16)
        byteBuf.writeBytes(byteArrayOf(1, 2, 3))
        byteBuf.readerIndex(0)
        val buf = NettyByteBufIoBuf.borrowInbound(byteBuf)
        assertEquals(0, buf.readerIndex)
        assertEquals(3, buf.writerIndex)
        assertEquals(16, buf.capacity)
        assertEquals(1.toByte(), buf.readByte())
        assertEquals(2.toByte(), buf.readByte())
        assertEquals(3.toByte(), buf.readByte())
        buf.release()
    }

    @Test
    fun `borrow resets indices even when the underlying wrapper object is reused`() {
        val first = ByteBufAllocator.DEFAULT.directBuffer(8, 8)
        val a = NettyByteBufIoBuf.borrow(
            first,
            baseOffset = 0,
            initialWriterIndex = 0,
            lifecycleListener = NoOpLifecycleListener,
        )
        a.writeByteArray(byteArrayOf(9, 9, 9), 0, 3)
        assertEquals(3, a.writerIndex)
        assertTrue(a.release()) // refCnt 1 -> 0, wrapper (if pooled) returns to RECYCLER

        val second = ByteBufAllocator.DEFAULT.directBuffer(4, 4)
        val b = NettyByteBufIoBuf.borrow(
            second,
            baseOffset = 0,
            initialWriterIndex = 0,
            lifecycleListener = NoOpLifecycleListener,
        )
        // A recycled wrapper must never leak state from its previous binding.
        assertEquals(0, b.readerIndex)
        assertEquals(0, b.writerIndex)
        assertEquals(4, b.capacity)
        b.release()
    }

    @Test
    fun `borrow reuses a previously released wrapper instance on a FastThreadLocalThread`() {
        // io.netty.util.Recycler#get() only pools on an
        // io.netty.util.concurrent.FastThreadLocalThread — on any other
        // JVM thread (e.g. the JUnit runner thread) it unconditionally
        // returns a fresh, unpooled instance regardless of iteration
        // count (see Recycler.java's get(): it checks
        // FastThreadLocalThread.currentThreadWillCleanupFastThreadLocals()
        // before consulting the thread-local pool). Netty's own
        // EventLoopGroup threads are always FastThreadLocalThread
        // (DefaultThreadFactory.newThread()), so this test runs the
        // borrow/release loop on one to exercise the real production
        // pooling path. Recycler also samples new-handle creation
        // (default ratio=8: only 1-in-8 first-time allocations on an
        // empty pool become poolable, by design, to avoid growing the
        // pool from a short allocation burst) — enough cycles are looped
        // for the ratio gate to open. The exact ratio (default 8) is an
        // internal Netty tuning constant, not part of its public
        // contract, so BORROW_RELEASE_CYCLES is set an order of
        // magnitude higher than the documented default to keep this
        // assertion deterministic-in-practice even if a future Netty
        // version changes the ratio.
        var reused = false
        val thread = FastThreadLocalThread {
            val seen = mutableListOf<NettyByteBufIoBuf>()
            repeat(BORROW_RELEASE_CYCLES) {
                val byteBuf = ByteBufAllocator.DEFAULT.directBuffer(8, 8)
                val buf = NettyByteBufIoBuf.borrow(
                    byteBuf,
                    baseOffset = 0,
                    initialWriterIndex = 0,
                    lifecycleListener = NoOpLifecycleListener,
                )
                seen += buf
                buf.release()
            }
            reused = seen.toSet().size < seen.size
        }
        thread.start()
        thread.join(THREAD_JOIN_TIMEOUT_MS)
        assertFalse(thread.isAlive, "borrow/release loop did not finish within ${THREAD_JOIN_TIMEOUT_MS}ms")
        assertTrue(
            reused,
            "expected at least one wrapper object reuse across $BORROW_RELEASE_CYCLES borrow/release cycles on a FastThreadLocalThread",
        )
    }

    @Test
    fun `directly constructed instances are not recycled on release`() {
        // NettyByteBufIoBuf(byteBuf) (no recycleHandle) must not be pushed
        // to the shared RECYCLER — retainedSlice() results and test-only
        // constructions can outlive or diverge from the pool's assumptions.
        val byteBuf = ByteBufAllocator.DEFAULT.directBuffer(8, 8)
        val direct = NettyByteBufIoBuf(byteBuf)
        assertTrue(direct.release()) // must not throw even though recycleHandle is null
    }

    private companion object {
        private const val THREAD_JOIN_TIMEOUT_MS = 5_000L
        private const val BORROW_RELEASE_CYCLES = 256
    }
}
