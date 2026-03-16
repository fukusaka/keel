package io.github.keel.codec.websocket

import kotlinx.io.Sink
import kotlinx.io.write

/**
 * Writes [frame] to [sink] according to RFC 6455 §5.2.
 *
 * If [WsFrame.maskKey] is set, the payload is masked before writing.
 * Payload length extension (16-bit / 64-bit) is selected automatically.
 *
 * @throws IllegalArgumentException if a control frame payload exceeds 125 bytes.
 */
fun writeFrame(frame: WsFrame, sink: Sink) {
    require(!(frame.opcode.isControl && frame.payload.size > 125)) {
        "Control frame payload must not exceed 125 bytes, got ${frame.payload.size}"
    }

    val byte0 = (if (frame.fin) 0x80 else 0x00) or
        (if (frame.rsv1) 0x40 else 0x00) or
        (if (frame.rsv2) 0x20 else 0x00) or
        (if (frame.rsv3) 0x10 else 0x00) or
        frame.opcode.code
    sink.writeByte(byte0.toByte())

    val maskBit = if (frame.maskKey != null) 0x80 else 0x00
    val len = frame.payload.size
    when {
        len <= 125 -> sink.writeByte((maskBit or len).toByte())
        len <= 65535 -> {
            sink.writeByte((maskBit or 126).toByte())
            sink.writeByte((len shr 8).toByte())
            sink.writeByte((len and 0xFF).toByte())
        }
        else -> {
            sink.writeByte((maskBit or 127).toByte())
            val l = len.toLong()
            repeat(8) { i -> sink.writeByte((l shr ((7 - i) * 8)).toByte()) }
        }
    }

    if (frame.maskKey != null) {
        val key = frame.maskKey
        sink.writeByte((key shr 24).toByte())
        sink.writeByte((key shr 16).toByte())
        sink.writeByte((key shr 8).toByte())
        sink.writeByte(key.toByte())

        val keyBytes = ByteArray(4).also { k ->
            k[0] = (key shr 24).toByte()
            k[1] = (key shr 16).toByte()
            k[2] = (key shr 8).toByte()
            k[3] = key.toByte()
        }
        val masked = ByteArray(frame.payload.size) { i ->
            (frame.payload[i].toInt() xor keyBytes[i % 4].toInt()).toByte()
        }
        sink.write(masked)
    } else {
        sink.write(frame.payload)
    }
}
