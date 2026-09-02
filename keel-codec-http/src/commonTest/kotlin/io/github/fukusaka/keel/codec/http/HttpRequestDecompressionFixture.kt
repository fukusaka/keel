package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.CompressionRegistry
import io.github.fukusaka.keel.compression.Decoder
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.pipeline.PipelineHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.Pipeline as PipelineType

/**
 * The fixture shared by the [HttpRequestDecompressionHandler] tests: the
 * registry, the decoder stubs each limit test drives, and the chain plumbing
 * that stands in for a pipeline.
 *
 * The stubs ([LowerDecoder], [MultiplyDecoder]) are what keep these tests off
 * the zlib backend. The real round-trip is exercised by
 * `keel-compression-zlib`'s own tests; these focus on handler logic.
 *
 * Nested and `protected` rather than hoisted to package scope — `bufOf` and
 * `TestCtx` are names several sibling test files declare for themselves.
 */
internal abstract class HttpRequestDecompressionFixture {

    protected val registryWithLower = CompressionRegistry().apply {
        registerDecoder(LowerDecoder)
    }

    // ------------------------------------------------------------------ stubs

    /** Decodes `update`'s ASCII input by lowercasing it. 1:1 byte ratio. */
    protected object LowerDecoder : Decoder {
        override val name: String = "lower"
        override fun newSession(
            allocator: io.github.fukusaka.keel.buf.BufferAllocator,
            options: DecoderOptions,
        ): DecoderSession = object : DecoderSession {
            private var pending: ByteArray = ByteArray(0)
            override fun update(input: IoBuf, output: IoBuf): CodecStatus {
                val n = input.readableBytes
                if (n > 0) {
                    val tmp = ByteArray(n)
                    input.readByteArray(tmp, 0, n)
                    pending += tmp.decodeToString().lowercase().encodeToByteArray()
                }
                return drain(output)
            }

            override fun finish(output: IoBuf): CodecStatus {
                if (pending.isEmpty()) return CodecStatus.FINISHED
                return drain(output)
            }

            override fun reset() { pending = ByteArray(0) }
            override fun close() { pending = ByteArray(0) }

            private fun drain(output: IoBuf): CodecStatus {
                if (pending.isEmpty()) return CodecStatus.NEED_INPUT
                val cap = output.writableBytes
                val take = minOf(cap, pending.size)
                output.writeByteArray(pending, 0, take)
                pending = pending.copyOfRange(take, pending.size)
                return if (pending.isEmpty()) CodecStatus.NEED_INPUT else CodecStatus.NEED_OUTPUT
            }
        }
    }

    /**
     * MultiplyDecoder variant that records how many sessions it has
     * been asked to instantiate. Used by the L1 pre-reject tests to
     * assert that the handler short-circuits at entry without calling
     * `newSession` (i.e. no inflate cost paid for an obviously-too-big
     * advertised body).
     */
    protected class CountingDecoder(private val factor: Int) : Decoder {
        override val name: String = "x$factor"
        var sessionsOpened: Int = 0
            private set
        override fun newSession(
            allocator: io.github.fukusaka.keel.buf.BufferAllocator,
            options: DecoderOptions,
        ): DecoderSession {
            sessionsOpened++
            return object : DecoderSession {
                override fun update(input: IoBuf, output: IoBuf): CodecStatus = CodecStatus.NEED_INPUT
                override fun finish(output: IoBuf): CodecStatus = CodecStatus.FINISHED
                override fun reset() = Unit
                override fun close() = Unit
            }
        }
    }

    /**
     * Decoder that emits each input byte [factor] times. Useful for
     * exercising ratio-cap + absolute-cap thresholds deterministically:
     * `MultiplyDecoder(200)` produces a 200:1 expansion ratio.
     */
    protected class MultiplyDecoder(private val factor: Int) : Decoder {
        override val name: String = "x$factor"
        override fun newSession(
            allocator: io.github.fukusaka.keel.buf.BufferAllocator,
            options: DecoderOptions,
        ): DecoderSession = object : DecoderSession {
            private var pending: ByteArray = ByteArray(0)
            override fun update(input: IoBuf, output: IoBuf): CodecStatus {
                val n = input.readableBytes
                if (n > 0) {
                    val tmp = ByteArray(n)
                    input.readByteArray(tmp, 0, n)
                    val expanded = ByteArray(n * factor)
                    for (i in 0 until n) {
                        for (j in 0 until factor) {
                            expanded[i * factor + j] = tmp[i]
                        }
                    }
                    pending += expanded
                }
                return drain(output)
            }

            override fun finish(output: IoBuf): CodecStatus {
                if (pending.isEmpty()) return CodecStatus.FINISHED
                return drain(output)
            }

            override fun reset() { pending = ByteArray(0) }
            override fun close() { pending = ByteArray(0) }

            private fun drain(output: IoBuf): CodecStatus {
                if (pending.isEmpty()) return CodecStatus.NEED_INPUT
                val cap = output.writableBytes
                val take = minOf(cap, pending.size)
                output.writeByteArray(pending, 0, take)
                pending = pending.copyOfRange(take, pending.size)
                return if (pending.isEmpty()) CodecStatus.NEED_INPUT else CodecStatus.NEED_OUTPUT
            }
        }
    }

    /**
     * Counts open sessions for the multiplier decoder so a leak across
     * requests is observable.
     */
    protected class CountingMultiplyDecoder(private val factor: Int) : Decoder {
        var openSessions: Int = 0
            private set

        override val name: String = "x$factor"

        override fun newSession(allocator: BufferAllocator, options: DecoderOptions): DecoderSession {
            openSessions++
            val delegate = MultiplyDecoder(factor).newSession(allocator, options)
            return object : DecoderSession by delegate {
                override fun close() {
                    delegate.close()
                    openSessions--
                }
            }
        }
    }

    /**
     * Bounded variant of [MultiplyDecoder] that throws `IllegalStateException`
     * once `update` is called more than [maxCalls] times. Used to detect
     * `decodeAggregated` infinite-loop regressions without relying on a wall-
     * clock timeout (the bug is a synchronous tight loop).
     */
    protected class BoundedCallMultiplyDecoder(
        private val factor: Int,
        private val maxCalls: Int,
    ) : Decoder {
        override val name: String = "x$factor"
        override fun newSession(allocator: BufferAllocator, options: DecoderOptions): DecoderSession {
            val delegate = MultiplyDecoder(factor).newSession(allocator, options)
            return object : DecoderSession by delegate {
                private var calls = 0
                override fun update(input: IoBuf, output: IoBuf): CodecStatus {
                    if (++calls > maxCalls) {
                        error("update() exceeded $maxCalls calls — likely infinite loop in decodeAggregated")
                    }
                    return delegate.update(input, output)
                }
            }
        }
    }

    // ------------------------------------------------------------------ chain plumbing

    protected class ChainState {
        val writes: MutableList<Any> = mutableListOf()
        val reads: MutableList<Any> = mutableListOf()
    }

    protected class TestCtx(
        val state: ChainState,
        // Injects a synchronous failure: invoked before each inbound message
        // is recorded, so returning normally records the read and throwing
        // aborts it (simulating a downstream handler rejecting a decoded
        // chunk). Used to pin the propagateRead ownership-on-throw contract
        // — if `emit` is not released on throw it leaks per aborted chunk.
        private val beforeRead: ((Any) -> Unit)? = null,
        override val allocator: io.github.fukusaka.keel.buf.BufferAllocator = DefaultAllocator,
    ) : PipelineHandlerContext {
        override val name: String get() = "test"
        override val pipeline: PipelineType get() = error("not used")
        override val channel: PipelinedChannel get() = error("not used")
        override val handler: PipelineHandler get() = error("not used")
        override fun propagateRead(msg: Any) {
            beforeRead?.invoke(msg)
            state.reads.add(msg)
        }
        override fun propagateActive() {}
        override fun propagateInactive() {}
        override fun propagateReadComplete() {}

        override fun propagateFlushComplete() {}
        override fun propagateError(cause: Throwable) {}
        override fun propagateUserEvent(event: Any) {}
        override fun propagateWritabilityChanged(isWritable: Boolean) {}
        override fun propagateWrite(msg: Any) { state.writes.add(msg) }
        override fun propagateFlush() {}
        override fun propagateClose() {}
    }

    /**
     * Builds a [TestCtx] that throws once on the first decoded [HttpBody]
     * (non-end) read — aborting at the moment `emitDecodedChunk` hands the
     * freshly allocated emit IoBuf downstream.
     *
     * Crucially this does NOT release the buffer before throwing: in
     * production the pipeline contract is "ownership transfers only when
     * propagate returns normally", so a downstream throw leaves the
     * buffer with the source. Releasing here would mask the very leak
     * the test is designed to catch.
     */
    protected fun ctxAbortingFirstDecodedBody(
        state: ChainState,
        allocator: io.github.fukusaka.keel.buf.BufferAllocator = DefaultAllocator,
    ): TestCtx {
        var thrown = false
        return TestCtx(state, beforeRead = { msg ->
            if (!thrown && msg is HttpBody && msg !is HttpBodyEnd) {
                thrown = true
                throw IllegalStateException("simulated downstream rejection of decoded body chunk")
            }
        }, allocator = allocator)
    }

    protected fun bufOf(text: String): IoBuf {
        val bytes = text.encodeToByteArray()
        val buf = DefaultAllocator.allocate(bytes.size)
        buf.writeByteArray(bytes, 0, bytes.size)
        return buf
    }

    protected fun ioBufAsString(buf: IoBuf): String {
        val n = buf.readableBytes
        if (n == 0) return ""
        val tmp = ByteArray(n)
        buf.readByteArray(tmp, 0, n)
        return tmp.decodeToString()
    }
}
