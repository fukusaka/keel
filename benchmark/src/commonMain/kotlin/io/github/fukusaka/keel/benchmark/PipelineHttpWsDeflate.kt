package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
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
     */
    fun compress(payload: ByteArray): ByteArray {
        val deflated = runEncoder(payload)
        encoder.reset()
        return stripSyncTail(deflated)
    }

    /**
     * Decompresses one inbound compressed message payload
     * (RFC 7692 §7.2.2): appends the `00 00 FF FF` sync tail, then
     * inflates. The session is reset afterwards (no context takeover).
     */
    fun decompress(payload: ByteArray): ByteArray {
        val inflated = runDecoder(payload + SYNC_TAIL)
        decoder.reset()
        return inflated
    }

    override fun close() {
        encoder.close()
        decoder.close()
    }

    /**
     * Drives [encoder] over [input] with the standard keel compression
     * SPI loop: `update` until `NEED_INPUT`, then `finish` until
     * `FINISHED`, draining [output] on every `NEED_OUTPUT`. Mirrors the
     * loop shape of keel-server-websocket's `WsPermessageDeflate`.
     */
    private fun runEncoder(input: ByteArray): ByteArray {
        val src = allocator.allocate(input.size.coerceAtLeast(1))
        val output = allocator.allocate(OUTPUT_CHUNK)
        val collected = ArrayList<Byte>(input.size)
        try {
            if (input.isNotEmpty()) src.writeByteArray(input, 0, input.size)
            while (true) {
                when (encoder.update(src, output)) {
                    CodecStatus.NEED_OUTPUT -> drain(output, collected)
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update must not return FINISHED")
                }
            }
            drain(output, collected)
            // Z_SYNC_FLUSH boundary (NOT finish): ends in 00 00 FF FF, stream
            // stays open. flush() returns NEED_INPUT once fully drained.
            while (encoder.flush(output) != CodecStatus.NEED_INPUT) {
                drain(output, collected)
            }
            drain(output, collected)
        } finally {
            src.release()
            output.release()
        }
        return collected.toByteArray()
    }

    /** Drives [decoder] to completion over [input]; same SPI loop as [runEncoder]. */
    private fun runDecoder(input: ByteArray): ByteArray {
        val src = allocator.allocate(input.size.coerceAtLeast(1))
        val output = allocator.allocate(OUTPUT_CHUNK)
        val collected = ArrayList<Byte>(input.size * INFLATE_GUESS_RATIO)
        try {
            if (input.isNotEmpty()) src.writeByteArray(input, 0, input.size)
            while (true) {
                when (decoder.update(src, output)) {
                    CodecStatus.NEED_OUTPUT -> drain(output, collected)
                    CodecStatus.NEED_INPUT -> break
                    CodecStatus.FINISHED -> error("update must not return FINISHED")
                }
            }
            drain(output, collected)
            // flush() (NOT finish): drain this frame's plaintext, stream open.
            while (decoder.flush(output) != CodecStatus.NEED_INPUT) {
                drain(output, collected)
            }
            drain(output, collected)
        } finally {
            src.release()
            output.release()
        }
        return collected.toByteArray()
    }

    /** Copies all readable bytes out of [output] into [dest] and clears it. */
    private fun drain(output: IoBuf, dest: ArrayList<Byte>) {
        val n = output.readableBytes
        if (n == 0) return
        val tmp = ByteArray(n)
        output.readByteArray(tmp, 0, n)
        for (b in tmp) dest.add(b)
        output.clear()
    }

    private fun ArrayList<Byte>.toByteArray(): ByteArray = ByteArray(size) { this[it] }

    /**
     * Removes the RFC 7692 §7.2.1 `00 00 FF FF` sync-flush tail from a
     * `Z_SYNC_FLUSH`-terminated DEFLATE stream.
     */
    private fun stripSyncTail(deflated: ByteArray): ByteArray =
        if (deflated.size >= SYNC_TAIL.size && deflated.takeLast(SYNC_TAIL.size) == SYNC_TAIL.toList()) {
            deflated.copyOf(deflated.size - SYNC_TAIL.size)
        } else {
            deflated
        }

    companion object {
        /**
         * The DEFLATE `Z_SYNC_FLUSH` tail (RFC 7692 §7.2.1): an empty
         * stored block. Stripped from outbound messages, re-appended to
         * inbound ones.
         */
        private val SYNC_TAIL: ByteArray = byteArrayOf(0x00, 0x00, 0xFF.toByte(), 0xFF.toByte())

        /** Output IoBuf size for the streaming codec drive loop. */
        private const val OUTPUT_CHUNK: Int = 8192

        /** Rough initial-capacity multiplier for the inflate accumulator. */
        private const val INFLATE_GUESS_RATIO: Int = 4

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
