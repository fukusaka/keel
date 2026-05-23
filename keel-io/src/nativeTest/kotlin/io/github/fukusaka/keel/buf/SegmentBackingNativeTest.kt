package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.allocArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalForeignApi::class)
class SegmentBackingNativeTest {

    @Test
    fun asNativePointer_returns_underlying_pointer() {
        val ptr = nativeHeap.allocArray<ByteVar>(64)
        try {
            val backing: SegmentBacking = NativeHeapBacking(ptr)
            assertEquals(ptr.rawValue.toLong(), backing.asNativePointer().rawValue.toLong())
        } finally {
            nativeHeap.free(ptr.rawValue)
        }
    }

    @Test
    fun asNativePointer_throws_for_non_native_backing() {
        val backing: SegmentBacking = FakeSegmentBacking
        assertFailsWith<ClassCastException> {
            backing.asNativePointer()
        }
    }
}
