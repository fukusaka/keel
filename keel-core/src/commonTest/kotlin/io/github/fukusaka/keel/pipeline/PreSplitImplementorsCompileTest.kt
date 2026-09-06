package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * A witness that the peer's end of file can be added to these interfaces
 * without an implementor written before it having to change.
 *
 * Each type below implements only what existed before the event, and the file
 * has to compile: a member added without a default turns that into a build
 * failure here rather than into a break a user finds. Two such members reached
 * review — one on the transport and one on the pipeline — and nothing in the
 * tree would have caught either, since every implementation in it is keel's
 * own and was updated with the change.
 *
 * The bodies are the smallest thing that compiles; nothing here is exercised.
 */
class PreSplitImplementorsCompileTest {

    private class PreSplitTransport : IoTransport {
        override val allocator: BufferAllocator get() = DefaultAllocator
        override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined
        override val inOwningContext: Boolean get() = true
        override val canDispatchToOwningContext: Boolean get() = true
        override val isOpen: Boolean get() = false
        override val isWritable: Boolean get() = false
        override var readEnabled: Boolean = false
        override var onRead: ((IoBuf) -> Unit)? = null
        override var onReadComplete: (() -> Unit)? = null
        override var onReadClosed: (() -> Unit)? = null
        override var onFlushComplete: (() -> Unit)? = null
        override var onWritabilityChanged: ((Boolean) -> Unit)? = null
        override var onConnectionFailure: ((Throwable) -> Unit)? = null

        override fun write(buf: IoBuf) {
            buf.release()
        }

        override fun flush(): Boolean = false

        override fun shutdownOutput() = Unit

        override fun close() = Unit

        override fun pauseReads() = Unit

        override fun resumeReads() = Unit

        override fun scheduleDeadline(delayMillis: Long, task: () -> Unit): TimerHandle? = null

        override suspend fun awaitPendingFlush() = Unit

        override suspend fun awaitClosed() = Unit

        override fun onChannelAttached() = Unit
    }

    @Test
    fun `a transport written before the peer's end of file keeps the contract it had`() {
        val transport = PreSplitTransport()
        assertNull(transport.onClosed, "the hook for the end alone stores nothing unless a transport carries it")
        transport.onClosed = { }
        assertNull(transport.onClosed, "and keeps storing nothing")
        assertFalse(
            transport.reportsEveryEndAsReadClosed,
            "a transport implementing the interface directly is written against the interface it reads",
        )
    }
}
