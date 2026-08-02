package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.core.IdleReadPolicy
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.util.ReferenceCountUtil
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Regression test for [NettyIoTransport]'s pooled flush-completion listener
 * (`FlushCompletionListener` / `flushListenerPool`) — the reusable
 * `GenericFutureListener` that replaced a per-flush Kotlin lambda closure
 * (each of which captured `writes`/`totalBytes`/`callback` and therefore
 * allocated a fresh object every call).
 *
 * [DeferredWriteHandler] intercepts outbound writes and holds their
 * [ChannelPromise]s uncompleted until the test resolves them, letting
 * multiple `flush()` generations have listeners outstanding simultaneously
 * — the scenario [NettyIoTransport]'s pool KDoc calls out as unsafe for a
 * fixed-size double-buffer (an earlier generation's listener must not be
 * aliased by a later `borrowFlushListener()` call before it fires).
 */
class NettyIoTransportFlushListenerPoolTest {

    private class DeferredWriteHandler : ChannelOutboundHandlerAdapter() {
        val pendingPromises = ArrayDeque<ChannelPromise>()
        val pendingMessages = ArrayDeque<Any>()

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            pendingMessages.addLast(msg)
            pendingPromises.addLast(promise)
        }

        fun complete(index: Int) {
            pendingPromises[index].setSuccess()
            ReferenceCountUtil.release(pendingMessages[index])
        }
    }

    private fun filledBuf(size: Int = 8) = DefaultAllocator.allocate(size).apply { writerIndex = size }

    private fun transportOn(ch: EmbeddedChannel): NettyIoTransport =
        NettyIoTransport(ch, DefaultAllocator, IdleReadPolicy.DETECT_PEER_CLOSE, flushCoalescing = false)

    @Test
    fun `multiple outstanding flush generations under backpressure each complete exactly once`() {
        val deferred = DeferredWriteHandler()
        val ch = EmbeddedChannel(deferred)
        val transport = transportOn(ch)

        var completions = 0
        transport.onFlushComplete = { completions++ }

        // Three flush() generations, each with its promise held uncompleted
        // (simulating a slow peer) before the previous one resolves.
        transport.write(filledBuf())
        transport.flush()
        transport.write(filledBuf())
        transport.flush()
        transport.write(filledBuf())
        transport.flush()

        assertEquals(0, completions, "no generation completes until its promise is resolved")
        assertEquals(3, deferred.pendingPromises.size)

        // Resolve out of order (gen 2 lands before gen 1 — a realistic
        // reordered-ack pattern) to prove the pooled listeners aren't
        // aliased across the outstanding generations.
        deferred.complete(1)
        assertEquals(1, completions, "resolving generation 2 must fire exactly its own listener")
        deferred.complete(0)
        assertEquals(
            2,
            completions,
            "resolving generation 1 afterward must not have been affected by generation 2's completion",
        )
        deferred.complete(2)
        assertEquals(3, completions)
    }

    @Test
    fun `sequential flushes settle into a reused listener without leaking completions`() {
        val ch = EmbeddedChannel()
        val transport = transportOn(ch)

        var completions = 0
        transport.onFlushComplete = { completions++ }

        repeat(50) {
            transport.write(filledBuf())
            transport.flush()
        }

        assertEquals(50, completions, "each of 50 sequential single-generation flushes must complete exactly once")
    }
}
