package io.github.fukusaka.keel.buf

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SegmentBackingJvmTest {

    @Test
    fun asByteBuffer_returns_underlying_direct_buffer() {
        val bb: ByteBuffer = ByteBuffer.allocateDirect(64)
        val backing: SegmentBacking = DirectByteBufferBacking(bb)
        assertSame(bb, backing.asByteBuffer())
    }

    @Test
    fun asByteBuffer_throws_for_non_direct_backing() {
        // FakeSegmentBacking implements SegmentBacking (via RawSegmentBacking)
        // but is not a DirectByteBufferBacking — exercising the
        // "future shared-memory carrier" cast-failure path.
        val backing: SegmentBacking = FakeSegmentBacking
        assertFailsWith<ClassCastException> {
            backing.asByteBuffer()
        }
    }
}
