package io.github.fukusaka.keel.buf

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract test for the minimum-viable [BufferAllocatorLifecycleListener] +
 * its [NoOpLifecycleListener] singleton. Verifies the no-op default does not
 * throw under any sequence and that a recording implementation observes
 * allocate / release events with the correct buffer identity.
 */
class BufferAllocatorLifecycleListenerTest {

    @Test
    fun `NoOpLifecycleListener accepts allocate and release without throwing`() {
        val buf = DefaultAllocator.allocate(64)
        try {
            NoOpLifecycleListener.onAllocated(buf)
            NoOpLifecycleListener.onReleased(buf)
        } finally {
            buf.release()
        }
    }

    @Test
    fun `recording listener observes both events with identity`() {
        val seen = mutableListOf<Pair<String, IoBuf>>()
        val listener = object : BufferAllocatorLifecycleListener {
            override fun onAllocated(buf: IoBuf) {
                seen += "alloc" to buf
            }
            override fun onReleased(buf: IoBuf) {
                seen += "release" to buf
            }
        }
        val buf = DefaultAllocator.allocate(128)
        try {
            listener.onAllocated(buf)
            listener.onReleased(buf)
        } finally {
            buf.release()
        }
        assertEquals(2, seen.size)
        assertEquals("alloc", seen[0].first)
        assertEquals("release", seen[1].first)
        // Same buffer instance on both events — listener can match for leak
        // detection.
        assertEquals(seen[0].second, seen[1].second)
    }
}
