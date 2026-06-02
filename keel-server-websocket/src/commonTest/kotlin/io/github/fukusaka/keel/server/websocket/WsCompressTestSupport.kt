package io.github.fukusaka.keel.server.websocket

/**
 * Materialises a [WsPermessageDeflate.CompressResult] to the contiguous wire
 * bytes a test feeds into `decompress` or a [io.github.fukusaka.keel.codec.websocket.WsFrame].
 *
 * For a compressed result this flattens and releases the pooled chunks
 * (mirroring what the frame encoder + a peer decoder do on the wire); for an
 * uncompressed result it returns the original payload.
 */
internal fun wireBytes(result: WsPermessageDeflate.CompressResult): ByteArray = when (result) {
    is WsPermessageDeflate.CompressResult.Uncompressed -> result.payload
    is WsPermessageDeflate.CompressResult.Compressed -> {
        val chunks = result.chunks
        val out = ByteArray(chunks.totalSize)
        var offset = 0
        chunks.forEach { chunk ->
            val n = chunk.readableBytes
            chunk.readByteArray(out, offset, n)
            offset += n
        }
        chunks.release()
        out
    }
}
