package io.github.fukusaka.keel.buf

import org.khronos.webgl.Int8Array
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class SegmentBackingJsTest {

    @Test
    fun asInt8Array_returns_underlying_typed_array() {
        val arr = Int8Array(64)
        val backing: SegmentBacking = Int8ArrayBacking(arr)
        assertSame(arr, backing.asInt8Array())
    }

    @Test
    fun asInt8Array_throws_for_non_typed_array_backing() {
        val backing: SegmentBacking = FakeSegmentBacking
        assertFailsWith<ClassCastException> {
            backing.asInt8Array()
        }
    }
}
