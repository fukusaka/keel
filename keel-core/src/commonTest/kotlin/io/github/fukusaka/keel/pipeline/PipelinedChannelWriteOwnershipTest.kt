package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.io.BufferedSuspendSink
import io.github.fukusaka.keel.logging.PrintLogger
import io.github.fukusaka.keel.testing.transport.TestIoTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/**
 * [PipelinedChannel.write] takes the buffer in every outcome: a write that
 * throws has released it or handed it on, so the caller — and a sink built
 * over the channel — has nothing left to release and nothing to release
 * twice. The cases land each throw the write can make: the closed channel's
 * refusal, an empty buffer, a cancellation while the hop to the loop is
 * still queued, a loop that refuses the hop, and a cancellation landing
 * after the hop ran, when the pipeline has the buffer.
 */
class PipelinedChannelWriteOwnershipTest {

    /** The loop the caller is not on: runs what it was handed only when told to. */
    private class LoopQueue : CoroutineDispatcher() {
        val queued = ArrayDeque<Runnable>()
        var inLoop = false
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = !inLoop
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queued.addLast(block)
        }

        fun runQueued() {
            inLoop = true
            try {
                while (queued.isNotEmpty()) queued.removeFirst().run()
            } finally {
                inLoop = false
            }
        }
    }

    /** A loop that has stopped: it takes nothing any more, the way Netty's refuses once its group is shut down. */
    private class RefusingLoop : CoroutineDispatcher() {
        override fun isDispatchNeeded(context: CoroutineContext): Boolean = true
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            throw IllegalStateException("loop has stopped")
        }
    }

    private fun channelOver(transport: TestIoTransport): AbstractPipelinedChannel =
        object : AbstractPipelinedChannel(transport, PrintLogger("write")) {}

    private fun TrackingAllocator.bytes(vararg v: Byte): IoBuf = allocate(8).also { b -> for (x in v) b.writeByte(x) }

    @Test
    fun `a write the closed channel refuses has released the buffer`() = runTest(timeout = 15.seconds) {
        val tracker = TrackingAllocator()
        val transport = TestIoTransport(tracker)
        val channel = channelOver(transport)
        transport.onClosed?.invoke() // the transport's end closes the channel
        assertFailsWith<IllegalStateException> { channel.write(tracker.bytes(1)) }
        tracker.assertNoLeaks()
    }

    @Test
    fun `a write the loop refuses after saying it could take it releases the buffer`() = runTest(timeout = 15.seconds) {
        // A loop that answers "I can take this" and then throws on the
        // hand-off leaves the message with nobody. The caller is told the
        // same as for a loop that said it could not, so what it handed over
        // is released rather than left outstanding.
        val tracker = TrackingAllocator()
        val transport = object : TestIoTransport(tracker) {
            override val inOwningContext: Boolean get() = false
            override val ioDispatcher: CoroutineDispatcher
                get() = object : CoroutineDispatcher() {
                    override fun dispatch(context: CoroutineContext, block: Runnable): Unit =
                        throw UnsupportedOperationException("the loop refuses")
                }
        }
        val channel = channelOver(transport)
        val buf = tracker.allocate(8).also { it.writeByte(1) }

        channel.pipeline.requestWrite(buf)

        assertEquals(0, transport.written.size, "nothing was queued")
        tracker.assertNoLeaks()
    }

    @Test
    fun `a write of nothing leaves the buffer with its caller`() = runTest(timeout = 15.seconds) {
        // The one outcome that takes nothing, because nothing was handed
        // anywhere: a caller that wrote a buffer it had not filled yet still
        // holds it, where releasing would return it to the pool underneath.
        val tracker = TrackingAllocator()
        val transport = TestIoTransport(tracker)
        val channel = channelOver(transport)
        val buf = tracker.allocate(8)

        assertEquals(0, channel.write(buf))

        assertEquals(0, transport.written.size, "nothing was queued for an empty write")
        assertEquals(1, tracker.outstandingCount, "and nothing was released for it")
        buf.release()
        tracker.assertNoLeaks()
        channel.close()
    }

    @Test
    fun `a write whose hop the loop refuses has released the buffer`() = runTest(timeout = 15.seconds) {
        val tracker = TrackingAllocator()
        val transport = TestIoTransport(tracker).apply {
            dispatcher = RefusingLoop()
            owningContext = false
        }
        val channel = channelOver(transport)
        val refusal = assertFailsWith<IllegalStateException> { channel.write(tracker.bytes(1)) }
        assertEquals("loop has stopped", refusal.message, "the loop's refusal reaches the caller as it is")
        assertEquals(0, transport.written.size, "the pipeline never took the buffer")
        tracker.assertNoLeaks()
        transport.owningContext = true
        channel.close()
    }

    @Test
    fun `a caller cancelled while the hop is queued has nothing left to release`() = runTest(timeout = 15.seconds) {
        val tracker = TrackingAllocator()
        val loop = LoopQueue()
        val transport = TestIoTransport(tracker).apply {
            dispatcher = loop
            owningContext = false
        }
        val channel = channelOver(transport)
        val writer = async(start = CoroutineStart.UNDISPATCHED) { channel.write(tracker.bytes(1)) }
        assertEquals(1, loop.queued.size, "the hop is queued, the caller parked")
        writer.cancel()
        loop.runQueued() // the loop finds the hop cancelled and skips it
        assertFailsWith<CancellationException> { writer.await() }
        assertEquals(0, transport.written.size, "the pipeline never took the buffer")
        tracker.assertNoLeaks()
        transport.owningContext = true
        channel.close()
    }

    @Test
    fun `a cancellation landing after the hop ran leaves the buffer with the pipeline to release once`() =
        runTest(timeout = 15.seconds) {
            val tracker = TrackingAllocator()
            val loop = LoopQueue()
            val transport = TestIoTransport(tracker).apply {
                dispatcher = loop
                owningContext = false
            }
            val channel = channelOver(transport)
            val writer = async(start = CoroutineStart.UNDISPATCHED) { channel.write(tracker.bytes(1)) }
            loop.runQueued() // the hop ran: the pipeline has the buffer, the caller's resume is queued
            assertEquals(1, transport.written.size, "the transport holds the buffer")
            writer.cancel() // lands on the way back
            assertFailsWith<CancellationException> { writer.await() }
            transport.releaseWritten() // the transport's release is the only one
            tracker.assertNoLeaks()
            transport.owningContext = true
            channel.close()
        }

    @Test
    fun `a buffered sink cancelled after its hand-off ran does not release the buffer twice`() =
        runTest(timeout = 15.seconds) {
            val tracker = TrackingAllocator()
            val loop = LoopQueue()
            val transport = TestIoTransport(tracker).apply {
                dispatcher = loop
                owningContext = false
            }
            val channel = channelOver(transport)
            val sink = BufferedSuspendSink(channel.asSuspendSink(), tracker)
            val writer = async(start = CoroutineStart.UNDISPATCHED) {
                sink.writeString("hello")
                sink.flush()
            }
            loop.runQueued() // the scratch buffer's hand-off ran
            assertEquals(1, transport.written.size, "the transport holds the scratch buffer")
            writer.cancel()
            assertFailsWith<CancellationException> { writer.await() }
            sink.close()
            transport.releaseWritten()
            tracker.assertNoLeaks()
            transport.owningContext = true
            channel.close()
        }
}
