package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The read-idle timeout ends with the read side, in the transport base —
 * so for every engine, not only the one whose seam pins it. Reporting the
 * peer's end of file cancels the timer that was armed, and arming it after
 * that report is a no-op.
 */
class IdleTimeoutAfterFinTest {

    /** A timer that only records; the test decides what fired. */
    private class RecordingTimer : EventLoopTimer {
        val scheduled = mutableListOf<Handle>()

        inner class Handle(val task: () -> Unit) : TimerHandle {
            var cancelled = false
            var touched = 0

            override fun touch() {
                touched++
            }

            override fun cancel() {
                cancelled = true
            }
        }

        override fun schedule(delayMillis: Long, task: () -> Unit): TimerHandle = Handle(task).also { scheduled += it }
    }

    /** The base with its protected idle entries opened to the test. */
    private class Transport(val timer: RecordingTimer) : TestIoTransport(TrackingAllocator()) {
        override val eventLoopTimer: EventLoopTimer get() = timer
        override val idleTimeoutMillis: Long get() = 1
        fun waitToRead() = armIdleTimeout()
        fun peerFin() = reportReadClosedOnce()
    }

    @Test
    fun `reporting the peer's end of file cancels the read-idle timer`() {
        val timer = RecordingTimer()
        val t = Transport(timer)
        var closed = 0
        t.onClosed = { closed++ }

        t.waitToRead()
        assertEquals(1, timer.scheduled.size, "premise: waiting to read arms the timer")

        t.peerFin()
        assertTrue(timer.scheduled.single().cancelled, "the timer went with the read side")
        assertEquals(0, closed, "and nothing reclaimed the connection")
    }

    @Test
    fun `arming the read-idle timer after the peer's end of file is a no-op`() {
        val timer = RecordingTimer()
        val t = Transport(timer)

        t.peerFin()
        t.waitToRead()
        assertEquals(0, timer.scheduled.size, "there is nothing to wait for")
    }
}
