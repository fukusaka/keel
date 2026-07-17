package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.codec.http.HttpBody
import io.github.fukusaka.keel.codec.http.HttpBodyEnd
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.CoroutineScope
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * [AbstractPipelinedWriteChannel] for the codec-http connection handler.
 *
 * [emit] wraps each body chunk in an [HttpBody] message so the HTTP response
 * encoder in the keel pipeline frames it correctly.  [writeTerminator] sends
 * [HttpBodyEnd.EMPTY] to signal the end of the response body.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class KeelByteWriteChannel(
    pipelinedChannel: PipelinedChannel,
    scope: CoroutineScope,
) : AbstractPipelinedWriteChannel(pipelinedChannel, scope) {

    override fun emit(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        // Large drains: wrap the snapshot array zero-copy instead of allocate+copy.
        // The array is this emit's exclusive snapshot (drained from the write
        // buffer, never touched again), so wrapBytes' no-mutation-until-release
        // contract holds. Without this, a drain larger than the allocator's
        // largest cached size class falls through to an unpooled exact-size
        // direct-buffer allocation — per response on large bodies, whose
        // reserve/free churn dominated the profile (Bits.reserveMemory) and
        // drove the p99 tail. Small drains keep the pooled copy: an 8 KiB
        // pooled buffer round-trip is cheaper than carrying a wrapped heap
        // array through the JVM transport's temp-direct copy.
        // Same pattern and threshold as BufferedSuspendSink's direct-write path.
        val wrapped = if (bytes.size >= LARGE_BODY_WRAP_THRESHOLD) {
            pipelinedChannel.allocator.wrapBytes(bytes, 0, bytes.size)
        } else {
            null
        }
        val ioBuf = wrapped ?: pipelinedChannel.allocator.allocate(bytes.size).also {
            it.writeByteArray(bytes, 0, bytes.size)
        }
        pipelinedChannel.pipeline.requestWrite(HttpBody(ioBuf))
        pipelinedChannel.pipeline.requestFlush()
    }

    override fun writeTerminator() {
        pipelinedChannel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
        pipelinedChannel.pipeline.requestFlush()
    }

    /**
     * Suspends until [writeTerminator] has been written and the final flush
     * confirmed, or until [cancel] is called.
     *
     * Called by [KeelApplicationResponse.awaitWriteComplete] so the connection
     * handler does not advance to the next request before the current response's
     * `HttpBodyEnd` has been written to the encoder — preventing the encoder's
     * `check(streamingMode == NONE)` from firing when a keep-alive client
     * reuses the connection immediately.
     */
    internal suspend fun awaitTerminated() = terminationDeferred.await()
}

/**
 * Body size at or above which the ktor adapter wraps the caller's array
 * zero-copy via [io.github.fukusaka.keel.buf.BufferAllocator.wrapBytes]
 * instead of allocate+copy ([KeelByteWriteChannel.emit] drains and
 * [KeelApplicationResponse.respondFromBytes] aggregated bodies). Matches
 * the direct-write threshold of
 * [io.github.fukusaka.keel.io.BufferedSuspendSink] (one pooled buffer):
 * below it, the pooled copy stays on the allocator's hottest size class;
 * above it, copying would either split the body or fall through to an
 * unpooled exact-size allocation per response.
 */
internal const val LARGE_BODY_WRAP_THRESHOLD = 8192
