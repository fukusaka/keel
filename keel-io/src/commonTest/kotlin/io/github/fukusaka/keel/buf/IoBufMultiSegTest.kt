package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cross-platform tests for the multi-segment surface added to [IoBuf]
 * in PR-2 (single-segment behaviour + overflow contract). Cross-segment
 * byte-op walks use real platform backings and live in the per-platform
 * test files (`DirectIoBufMultiSegTest`, `NativeIoBufMultiSegTest`,
 * `TypedArrayIoBufMultiSegTest`) — those exercise the slow paths against
 * actual memory.
 */
class IoBufMultiSegTest {

    @Test
    fun newly_allocated_buf_is_single_seg_with_matching_maxCapacity() {
        val buf = createDefaultIoBuf(64)
        try {
            assertEquals(64, buf.capacity)
            assertEquals(64, buf.maxCapacity)
        } finally {
            buf.release()
        }
    }

    @Test
    fun appendSegment_on_default_buf_throws_overflow_at_cap() {
        // createDefaultIoBuf has maxCapacity == capacity, so any append overflows.
        val buf = createDefaultIoBuf(64)
        val extra = makeTestSegment(32)
        try {
            assertFailsWith<KeelBufferOverflowException> {
                buf.appendSegment(extra)
            }
        } finally {
            extra.release()
            buf.release()
        }
    }

    @Test
    fun forEachReadableSegment_emits_one_window_for_single_seg() {
        val buf = createDefaultIoBuf(64)
        try {
            buf.writeAscii("HELLO", 0, 5)
            var count = 0
            var length = 0
            buf.forEachReadableSegment { _, _, len ->
                count++
                length += len
            }
            assertEquals(1, count)
            assertEquals(5, length)
        } finally {
            buf.release()
        }
    }

    @Test
    fun fillReadableSegments_emits_one_window_for_single_seg() {
        val buf = createDefaultIoBuf(64)
        val list = SegmentRangeList()
        try {
            buf.writeAscii("HELLO", 0, 5)
            buf.fillReadableSegments(list)
            assertEquals(1, list.size)
            assertEquals(5, list[0].length)
        } finally {
            buf.release()
        }
    }
}
