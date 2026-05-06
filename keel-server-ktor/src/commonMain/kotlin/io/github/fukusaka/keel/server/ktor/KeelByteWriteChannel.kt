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
        val ioBuf = pipelinedChannel.allocator.allocate(bytes.size)
        ioBuf.writeByteArray(bytes, 0, bytes.size)
        pipelinedChannel.pipeline.requestWrite(HttpBody(ioBuf))
        pipelinedChannel.pipeline.requestFlush()
    }

    override fun writeTerminator() {
        pipelinedChannel.pipeline.requestWrite(HttpBodyEnd.EMPTY)
        pipelinedChannel.pipeline.requestFlush()
    }
}
