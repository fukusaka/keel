package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LongPriorityQueueTest {
    @Test
    fun `poll returns values in ascending order`() {
        val q = LongPriorityQueue()
        listOf(5L, 1L, 3L, 2L, 4L).forEach { q.offer(it) }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), generateSequence { q.poll() }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList())
    }

    @Test
    fun `empty queue reports NO_VALUE`() {
        val q = LongPriorityQueue()
        assertTrue(q.isEmpty())
        assertEquals(LongPriorityQueue.NO_VALUE, q.poll())
        assertEquals(LongPriorityQueue.NO_VALUE, q.peek())
    }

    @Test
    fun `remove pulls an arbitrary element and keeps heap order`() {
        val q = LongPriorityQueue()
        listOf(10L, 20L, 30L, 40L, 50L).forEach { q.offer(it) }
        q.remove(30L)
        assertEquals(listOf(10L, 20L, 40L, 50L), generateSequence { q.poll() }.takeWhile { it != LongPriorityQueue.NO_VALUE }.toList())
    }

    @Test
    fun `remove of a missing value is a no-op`() {
        val q = LongPriorityQueue()
        q.offer(1L)
        q.remove(99L)
        assertEquals(1L, q.poll())
    }

    @Test
    fun `peek does not remove`() {
        val q = LongPriorityQueue()
        q.offer(7L)
        assertEquals(7L, q.peek())
        assertEquals(7L, q.peek())
        assertEquals(7L, q.poll())
        assertTrue(q.isEmpty())
    }

    @Test
    fun `grows past initial capacity`() {
        val q = LongPriorityQueue()
        for (i in 100 downTo 1) q.offer(i.toLong())
        for (i in 1..100) assertEquals(i.toLong(), q.poll())
    }
}
