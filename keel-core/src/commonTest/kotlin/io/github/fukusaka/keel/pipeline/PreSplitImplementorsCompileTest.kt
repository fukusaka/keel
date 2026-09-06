package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A witness that the peer's end of file can be added to these interfaces
 * without an implementor written before it having to change.
 *
 * Each type below implements only what existed before the event, and the file
 * has to compile: a member added to one of them without a default turns that
 * into a build failure here rather than into a break a user finds. Two such
 * members reached review — one on the transport and one on the pipeline — and
 * nothing in the tree would have caught either, since every implementation in
 * it is keel's own and was updated with the change.
 *
 * [PipelinedChannel] is not among them: it is implemented here only by
 * `AbstractPipelinedChannel`, whose members a subclass inherits, so an
 * addition to it is not the same kind of break.
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

    /** A pipeline from before the event, which must still compile and run. */
    private class PreSplitPipeline : Pipeline {
        override val channel: PipelinedChannel get() = throw UnsupportedOperationException()
        override val isEmpty: Boolean get() = true

        override fun addFirst(name: String, handler: PipelineHandler): Pipeline = this

        override fun addLast(name: String, handler: PipelineHandler): Pipeline = this

        override fun addBefore(baseName: String, name: String, handler: PipelineHandler): Pipeline = this

        override fun addAfter(baseName: String, name: String, handler: PipelineHandler): Pipeline = this

        override fun remove(name: String): PipelineHandler = throw UnsupportedOperationException()

        override fun replace(oldName: String, newName: String, newHandler: PipelineHandler): PipelineHandler =
            throw UnsupportedOperationException()

        override fun get(name: String): PipelineHandler? = null

        override fun context(name: String): PipelineHandlerContext? = null

        override fun notifyActive(): Pipeline = this

        override fun notifyRead(msg: Any): Pipeline = this

        override fun notifyReadComplete(): Pipeline = this

        override fun notifyFlushComplete(): Pipeline = this

        override fun notifyInactive(): Pipeline = this

        override fun notifyError(cause: Throwable): Pipeline = this

        override fun notifyUserEvent(event: Any): Pipeline = this

        override fun notifyWritabilityChanged(isWritable: Boolean): Pipeline = this

        override fun requestWrite(msg: Any): Pipeline = this

        override fun requestFlush(): Pipeline = this

        override fun requestClose(): Pipeline = this
    }

    /** A handler context from before the event. */
    private class PreSplitContext : PipelineHandlerContext {
        override val channel: PipelinedChannel get() = throw UnsupportedOperationException()
        override val pipeline: Pipeline get() = throw UnsupportedOperationException()
        override val name: String get() = "pre-split"
        override val handler: PipelineHandler get() = throw UnsupportedOperationException()
        override val allocator: BufferAllocator get() = DefaultAllocator

        override fun propagateActive() = Unit

        override fun propagateRead(msg: Any) = Unit

        override fun propagateReadComplete() = Unit

        override fun propagateFlushComplete() = Unit

        override fun propagateInactive() = Unit

        override fun propagateError(cause: Throwable) = Unit

        override fun propagateUserEvent(event: Any) = Unit

        override fun propagateWritabilityChanged(isWritable: Boolean) = Unit

        override fun propagateWrite(msg: Any) = Unit

        override fun propagateFlush() = Unit

        override fun propagateClose() = Unit
    }

    @Test
    fun `a pipeline and a context written before it keep the contract they had`() {
        val pipeline: Pipeline = PreSplitPipeline()
        assertSame(pipeline, pipeline.notifyReadClosed(), "the event is one a pre-split pipeline does nothing with")
        PreSplitContext().propagateReadClosed()
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
