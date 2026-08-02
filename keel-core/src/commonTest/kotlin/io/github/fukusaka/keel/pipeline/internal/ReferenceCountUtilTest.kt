package io.github.fukusaka.keel.pipeline.internal

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.Releasable
import kotlin.test.Test
import kotlin.test.assertTrue

class ReferenceCountUtilTest {

    @Test
    fun `safeRelease releases IoBuf`() {
        val buf = DefaultAllocator.allocate(8)
        buf.writeByte(0x42)
        ReferenceCountUtil.safeRelease(buf)
    }

    @Test
    fun `safeRelease on already-released IoBuf does not throw`() {
        val buf = DefaultAllocator.allocate(4)
        buf.release()
        ReferenceCountUtil.safeRelease(buf) // double-release: must not throw
    }

    @Test
    fun `safeRelease calls release on Releasable`() {
        var released = false
        val releasable = object : Releasable {
            override fun release(): Boolean {
                released = true
                return true
            }
        }
        ReferenceCountUtil.safeRelease(releasable)
        assertTrue(released, "expected release() to be called on Releasable")
    }

    @Test
    fun `safeRelease swallows IllegalStateException from Releasable release`() {
        val releasable = object : Releasable {
            override fun release(): Boolean = throw IllegalStateException("already released")
        }
        ReferenceCountUtil.safeRelease(releasable) // must not throw
    }

    @Test
    fun `safeRelease ignores non-releasable message`() {
        ReferenceCountUtil.safeRelease("plain string message") // must not throw
    }
}
