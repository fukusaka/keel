package io.github.fukusaka.keel.pipeline.internal

import io.github.fukusaka.keel.buf.DefaultAllocator
import kotlin.test.Test
import kotlin.test.assertTrue

class ReferenceCountUtilTest {

    @Test
    fun `safeRelease releases IoBuf`() {
        val buf = DefaultAllocator.allocate(8)
        buf.writeByte(0x42)
        ReferenceCountUtil.safeRelease(buf)
        // After release the refcount is 0 — a second release must throw.
        // We verify indirectly: no exception from safeRelease above.
    }

    @Test
    fun `safeRelease on already-released IoBuf does not throw`() {
        val buf = DefaultAllocator.allocate(4)
        buf.release()
        ReferenceCountUtil.safeRelease(buf) // double-release: must not throw
    }

    @Test
    fun `safeRelease calls close on AutoCloseable`() {
        var closed = false
        val closeable = AutoCloseable { closed = true }
        ReferenceCountUtil.safeRelease(closeable)
        assertTrue(closed, "expected close() to be called on AutoCloseable")
    }

    @Test
    fun `safeRelease swallows IllegalStateException from AutoCloseable close`() {
        val closeable = AutoCloseable { throw IllegalStateException("already closed") }
        ReferenceCountUtil.safeRelease(closeable) // must not throw
    }

    @Test
    fun `safeRelease ignores non-releasable message`() {
        ReferenceCountUtil.safeRelease("plain string message") // must not throw
    }
}
