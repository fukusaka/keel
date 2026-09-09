package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBufAccumulator
import io.github.fukusaka.keel.buf.IoBufChunks
import io.github.fukusaka.keel.compression.CodecStatus
import io.github.fukusaka.keel.compression.DecoderOptions
import io.github.fukusaka.keel.compression.DecoderSession
import io.github.fukusaka.keel.compression.EncoderOptions
import io.github.fukusaka.keel.compression.EncoderSession
import io.github.fukusaka.keel.compression.FlushMode
import io.github.fukusaka.keel.compression.WrapFormat
import io.github.fukusaka.keel.compression.zlib.DeflateCodec

/**
 * Bench-only, frame-level `permessage-deflate` (RFC 7692) engine for the
 * `pipeline-http-*` benchmark servers.
 *
 * The `pipeline-http-*` engines drive WebSocket traffic through a manual
 * [io.github.fukusaka.keel.pipeline.InboundHandler] that never goes
 * through `runWebSocketUpgrade` / `WsSession`, so they cannot reuse
 * keel-server-websocket's `WsPermessageDeflate` — that class is
 * `internal` to keel-server-websocket and not importable here. This is
 * the deliberately-bespoke pipeline-level counterpart: it consumes the
 * same `keel-compression` SPI and `DeflateCodec` backend and applies the
 * identical RFC 7692 wire transform (raw DEFLATE, `Z_SYNC_FLUSH`,
 * `00 00 FF FF` tail strip / append).
 *
 * The bench `/ws-deflate` workload sends single-frame messages, so this
 * engine works per-frame: [decompress] one inbound frame payload,
 * [compress] it back for the echo. Multi-frame message aggregation
 * (RSV1 on the lead frame only, RFC 7692 §6) is intentionally out of
 * scope — the productized path in keel-server-websocket covers that.
 *
 * Operates with `contextTakeover = false` (keel's no-context-takeover
 * default), so the encoder / decoder sessions are reset after every
 * message and no LZ77 window carries across messages.
 *
 * ### Thread safety
 *
 * **Not thread-safe.** One instance is owned per connection by the
 * pipeline's routing handler and driven from that connection's single
 * EventLoop thread.
 */
internal class PipelineHttpWsDeflate : AutoCloseable {

    private val allocator = DefaultAllocator

    private val encoder: EncoderSession = DeflateCodec.encoder.newSession(
        allocator,
        EncoderOptions(
            // update() feeds; the per-message Z_SYNC_FLUSH boundary is emitted
            // explicitly via flush() (mirrors WsPermessageDeflate after #650).
            wrapFormat = WrapFormat.Raw,
            flushMode = FlushMode.NoFlush,
            contextTakeover = false,
        ),
    )

    private val decoder: DecoderSession = DeflateCodec.decoder.newSession(
        allocator,
        DecoderOptions(
            wrapFormat = WrapFormat.Raw,
            contextTakeover = false,
        ),
    )

    /**
     * Compresses one outbound message payload (RFC 7692 §7.2.1): raw
     * DEFLATE with `Z_SYNC_FLUSH`, then strips the trailing `00 00 FF FF`
     * sync marker. The session is reset afterwards (no context takeover).
     *
     * Returns the pooled chunks the codec wrote into, not a `ByteArray`:
     * the caller hands them to `WsFrame.payloadChunks`, which the frame
     * encoder writes one by one, so the compressed payload is never copied.
     * Flattening here and letting the encoder copy it back into an `IoBuf`
     * is what production stopped doing, and the point of this class is to
     * measure what production runs. **The returned chunks are owned by the
     * caller** — hand them to a `WsFrame` (whose encoder releases them) or
     * release them.
     */
    fun compressToChunks(payload: ByteArray): IoBufChunks {
        val chunks = runEncoderChunks(payload)
        encoder.reset()
        return chunks
    }

    /**
     * Decompresses one inbound compressed message payload
     * (RFC 7692 §7.2.2): appends the `00 00 FF FF` sync tail, then
     * inflates. The session is reset afterwards (no context takeover).
     */
    fun decompress(payload: ByteArray): ByteArray {
        val inflated = runDecoder(payload)
        decoder.reset()
        return inflated
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    /**
     * Drives [encoder] over [input] into an [IoBufAccumulator]: the codec
     * writes straight into pooled chunks (`NEED_OUTPUT` seals a full chunk),
     * so nothing is copied per drain and no byte is boxed.
     * [IoBufAccumulator.trimTail] strips the RFC 7692 `00 00 FF FF`
     * `Z_SYNC_FLUSH` marker. Statement-for-statement the loop in
     * keel-server-websocket's `WsPermessageDeflate.runEncoderChunks`.
     *
     * Releases the accumulator on the throw path only: [IoBufAccumulator.toIoBufChunks]
     * transfers the chunks **without** clearing them, so a `finally` here
     * would release every chunk a second time after the hand-off.
     */
    private fun runEncoderChunks(input: ByteArray): IoBufChunks {
        val src = allocator.allocate(input.size.coerceAtLeast(1))
        val acc = IoBufAccumulator(allocator, OUTPUT_CHUNK)
        try {
            if (input.isNotEmpty()) src.writeByteArray(input, 0, input.size)
            while (true) {
                when (encoder.update(src, acc.writableChunk())) {
                    CodecStatus.NEED_OUTPUT -> acc.commit()
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update must not return FINISHED")
                }
            }
            acc.commit()
            // Z_SYNC_FLUSH boundary (NOT finish): ends in 00 00 FF FF, stream
            // stays open. flush() returns NEED_INPUT once fully drained.
            while (encoder.flush(acc.writableChunk()) != CodecStatus.NEED_INPUT) {
                acc.commit()
            }
            acc.commit()
            // A NoFlush stream terminated by flush() always emits at least the
            // four marker bytes, so trimTail's fail-fast guard is unreachable
            // with the zlib backends. If a backend ever broke that contract the
            // throw would skip compressToChunks' reset and leave the encoder
            // mid-message; production poisons the session for that case, which
            // a bench engine that would already be reporting a broken workload
            // does not need to mirror.
            acc.trimTail(SYNC_TAIL.size)
            return acc.toIoBufChunks()
        } catch (t: Throwable) {
            acc.release()
            throw t
        } finally {
            src.release()
        }
    }

    /**
     * Drives [decoder] to completion over [input]; same accumulator loop as
     * [runEncoder]. The `00 00 FF FF` boundary is written straight into the
     * input buffer rather than concatenated onto a fresh `ByteArray`.
     */
    private fun runDecoder(input: ByteArray): ByteArray {
        val src = allocator.allocate(input.size + SYNC_TAIL.size)
        val acc = IoBufAccumulator(allocator, OUTPUT_CHUNK)
        try {
            if (input.isNotEmpty()) src.writeByteArray(input, 0, input.size)
            src.writeByteArray(SYNC_TAIL, 0, SYNC_TAIL.size)
            while (true) {
                when (decoder.update(src, acc.writableChunk())) {
                    CodecStatus.NEED_OUTPUT -> acc.commit()
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update must not return FINISHED")
                }
            }
            acc.commit()
            // flush() (NOT finish): drain this frame's plaintext, stream open.
            while (decoder.flush(acc.writableChunk()) != CodecStatus.NEED_INPUT) {
                acc.commit()
            }
            acc.commit()
            return acc.toByteArray()
        } catch (t: Throwable) {
            acc.release()
            throw t
        } finally {
            src.release()
        }
    }

    companion object {
        /**
         * The DEFLATE `Z_SYNC_FLUSH` tail (RFC 7692 §7.2.1): an empty
         * stored block. Stripped from outbound messages, re-appended to
         * inbound ones.
         */
        private val SYNC_TAIL: ByteArray = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())

        /** Accumulator chunk size for the streaming codec drive loop. */
        private const val OUTPUT_CHUNK: Int = 8192

        /**
         * The `Sec-WebSocket-Extensions` value sent in the 101 response
         * when a `permessage-deflate` offer is accepted. keel's
         * no-context-takeover policy always echoes both
         * `server_no_context_takeover` and `client_no_context_takeover`
         * (RFC 7692 §5.1), matching [PipelineHttpWsDeflate]'s
         * `contextTakeover = false` sessions.
         */
        const val RESPONSE_EXTENSION_HEADER: String =
            "permessage-deflate; server_no_context_takeover; client_no_context_takeover"

        /**
         * Returns true when [extensionsHeader] (a raw
         * `Sec-WebSocket-Extensions` request header value) contains a
         * `permessage-deflate` offer. A null or absent header means the
         * client did not offer the extension.
         */
        fun offersPermessageDeflate(extensionsHeader: String?): Boolean =
            extensionsHeader != null &&
                extensionsHeader.split(',').any { it.trim().startsWith("permessage-deflate") }
    }
}
