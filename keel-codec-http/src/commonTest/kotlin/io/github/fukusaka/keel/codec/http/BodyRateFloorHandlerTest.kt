package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.Pipeline
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.TimerHandle
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for [BodyRateFloorHandler]'s recurring window check, driven
 * deterministically: the test channel's [scheduleDeadline] override captures each
 * scheduled task instead of using a real timer, so a window check can be "fired" by
 * invoking its captured task. Every check uses the same interval, so the captured tasks
 * are addressed by their order of arming (index) rather than by timeout. No wall-clock
 * timing involved.
 */
class BodyRateFloorHandlerTest {

    private class FakeTimerHandle : TimerHandle {
        var cancelled = false
        override fun touch() = Unit
        override fun cancel() { cancelled = true }
    }

    private class Scheduled(val millis: Long, val task: () -> Unit, val handle: FakeTimerHandle)

    private val transport = TestIoTransport()
    private val scheduled = mutableListOf<Scheduled>()

    // Captures every scheduleDeadline so the test fires/inspects window checks by index.
    private val channel = object : AbstractPipelinedChannel(transport, PrintLogger("test")) {
        override fun scheduleDeadline(delayMillis: Long, task: () -> Unit): TimerHandle =
            FakeTimerHandle().also { scheduled.add(Scheduled(delayMillis, task, it)) }
    }

    /** Fires the i-th armed window check by invoking its captured task. */
    private fun fire(index: Int) = scheduled[index].task.invoke()

    /** Swallows propagated reads so an unconsumed message does not reach the tail. */
    private class Sink : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) = Unit
    }

    private fun pipelineWith(minBytesPerSec: Long): Pipeline {
        val p = channel.pipeline
        p.addLast("body-rate-floor", BodyRateFloorHandler(minBytesPerSec, CHECK_INTERVAL))
        p.addLast("sink", Sink())
        return p
    }

    private fun bufOfSize(n: Int): IoBuf {
        val buf = DefaultAllocator.allocate(n.coerceAtLeast(1))
        if (n > 0) buf.writeByteArray(ByteArray(n), 0, n)
        return buf
    }

    private fun head() = HttpRequestHead(HttpMethod.GET, "/")
    private fun body(n: Int) = HttpBody(bufOfSize(n))
    private fun bodyEnd(n: Int) = HttpBodyEnd(bufOfSize(n))

    // --- Arming ---

    @Test
    fun `the first body chunk arms a window check at the configured interval`() {
        val p = pipelineWith(MIN_RATE)
        p.notifyRead(body(REQUIRED))
        assertEquals(listOf(CHECK_INTERVAL), scheduled.map { it.millis }, "should arm exactly one window check")
        assertFalse(transport.closed, "arming must not close the channel")
    }

    @Test
    fun `further body chunks before the first check do not arm a second check`() {
        val p = pipelineWith(MIN_RATE)
        p.notifyRead(body(REQUIRED))
        p.notifyRead(body(REQUIRED))
        assertEquals(1, scheduled.size, "the recurring check is armed once per window not per chunk")
    }

    // --- Floor evaluation ---

    @Test
    fun `a window below the floor force-closes the slow-body peer`() {
        val p = pipelineWith(MIN_RATE)
        p.notifyRead(body(REQUIRED - 1))
        fire(0)
        assertTrue(transport.closed, "a body slower than the floor must be force-closed")
    }

    @Test
    fun `a window meeting the floor re-arms the check and keeps the connection`() {
        val p = pipelineWith(MIN_RATE)
        // First chunk is credited to the first window (lenient start).
        p.notifyRead(body(REQUIRED))
        fire(0)
        assertEquals(2, scheduled.size, "a passing window must re-arm the next check")
        assertFalse(transport.closed, "a body meeting the floor must not be closed")
        // Second window also meets the floor relative to the advanced window start.
        p.notifyRead(body(REQUIRED))
        fire(1)
        assertEquals(3, scheduled.size, "a second passing window re-arms again")
        assertFalse(transport.closed)
    }

    @Test
    fun `a stall after a passing window force-closes on the next check`() {
        val p = pipelineWith(MIN_RATE)
        p.notifyRead(body(REQUIRED))
        fire(0)
        // No further bytes arrive: the next window receives nothing.
        fire(1)
        assertTrue(transport.closed, "a stalled body after a good window must be force-closed")
    }

    // --- Completion / disarm ---

    @Test
    fun `a body end during streaming cancels the pending check`() {
        val p = pipelineWith(MIN_RATE)
        p.notifyRead(body(REQUIRED))
        p.notifyRead(bodyEnd(0))
        assertTrue(scheduled[0].handle.cancelled, "completing the body must cancel the pending check")
        assertFalse(transport.closed)
    }

    @Test
    fun `a body that completes in a single body end never arms a check`() {
        val p = pipelineWith(MIN_RATE)
        // A fast body delivered as one terminal chunk: no mid-body HttpBody is seen.
        p.notifyRead(bodyEnd(REQUIRED))
        assertTrue(scheduled.isEmpty(), "a one-shot body must not arm the rate-floor check")
        assertFalse(transport.closed)
    }

    @Test
    fun `a new request head resets the byte counters and cancels the check`() {
        val p = pipelineWith(MIN_RATE)
        p.notifyRead(body(REQUIRED))
        fire(0)
        // The next request reuses the keep-alive connection.
        p.notifyRead(head())
        assertTrue(scheduled[1].handle.cancelled, "a new request must cancel the previous request's check")
        // A fresh body whose first window meets the floor from a zeroed window start.
        p.notifyRead(body(REQUIRED + 20))
        fire(2)
        assertFalse(transport.closed, "the window start must reset so the new request is judged on its own bytes")
    }

    // --- Disabled / degradation ---

    @Test
    fun `a zero floor disables the check entirely`() {
        val p = pipelineWith(0)
        p.notifyRead(body(1))
        p.notifyRead(bodyEnd(0))
        assertTrue(scheduled.isEmpty(), "no check should be armed when the floor is disabled")
        assertFalse(transport.closed)
    }

    @Test
    fun `a missing engine timer disables enforcement without closing`() {
        // A plain channel whose transport wires no EventLoop timer: scheduleDeadline
        // returns null, so the check cannot be armed. The handler must degrade gracefully
        // (no crash, no force-close) rather than enforce or fault.
        val plainTransport = TestIoTransport()
        val plainChannel = object : AbstractPipelinedChannel(plainTransport, PrintLogger("test")) {}
        plainChannel.pipeline.addLast("body-rate-floor", BodyRateFloorHandler(MIN_RATE, CHECK_INTERVAL))
        plainChannel.pipeline.addLast("sink", Sink())
        plainChannel.pipeline.notifyRead(HttpBody(bufOfSize(1)))
        assertFalse(plainTransport.closed, "an unenforceable floor must not close the connection")
    }

    @Test
    fun `connection inactive cancels the pending check`() {
        val p = pipelineWith(MIN_RATE)
        p.notifyRead(body(REQUIRED))
        p.notifyInactive()
        assertTrue(scheduled[0].handle.cancelled, "inactive must cancel the pending window check")
    }

    private companion object {
        const val CHECK_INTERVAL = 1_000L
        const val MIN_RATE = 100L

        // requiredPerWindow = MIN_RATE * CHECK_INTERVAL / 1000 = 100 bytes.
        const val REQUIRED = 100
    }
}
