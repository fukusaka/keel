package io.github.fukusaka.keel.codec.websocket

import kotlinx.io.Source
import kotlinx.io.readByteArray

/**
 * Reads one WebSocket frame from [source] (RFC 6455 §5.2).
 *
 * Masked payloads are automatically unmasked.
 *
 * **Reserved bits**: RSV2 and RSV3 must always be zero — keel negotiates
 * no extension that uses them, so a non-zero value is a protocol error
 * ([IllegalArgumentException]). RSV1 is rejected the same way *unless*
 * [allowRsv1] is true: the `permessage-deflate` extension (RFC 7692
 * §7.2) sets RSV1=1 on the first frame of a compressed message, so a
 * server that negotiated that extension passes `allowRsv1 = true` to
 * accept the bit. RSV1 stays rejected by default to keep callers that
 * negotiated nothing strict.
 *
 * @param source the byte source to read one frame from.
 * @param allowRsv1 when true, permit RSV1=1 on the frame (a
 *   `permessage-deflate` compressed-message marker). RSV2/RSV3 stay
 *   rejected regardless. Defaults to false (current strict behaviour).
 * @throws IllegalArgumentException if the frame is malformed.
 */
fun parseFrame(source: Source, allowRsv1: Boolean = false): WsFrame {
    val byte0 = source.readByte().toInt() and 0xFF
    val byte1 = source.readByte().toInt() and 0xFF

    val fin = (byte0 and 0x80) != 0
    val rsv1 = (byte0 and 0x40) != 0
    val rsv2 = (byte0 and 0x20) != 0
    val rsv3 = (byte0 and 0x10) != 0

    require((allowRsv1 || !rsv1) && !rsv2 && !rsv3) {
        "Reserved bits invalid (rsv1=$rsv1, rsv2=$rsv2, rsv3=$rsv3): " +
            "RSV2/RSV3 must be 0; RSV1 is permitted only when permessage-deflate is negotiated"
    }

    val opcode = WsOpcode.fromCode(byte0 and 0x0F)
    val masked = (byte1 and 0x80) != 0
    val payloadLen7 = byte1 and 0x7F

    val payloadLength: Long = when (payloadLen7) {
        126 -> {
            val hi = source.readByte().toInt() and 0xFF
            val lo = source.readByte().toInt() and 0xFF
            ((hi shl 8) or lo).toLong()
        }
        127 -> {
            var len = 0L
            repeat(8) { len = (len shl 8) or (source.readByte().toInt() and 0xFF).toLong() }
            len
        }
        else -> payloadLen7.toLong()
    }

    if (opcode.isControl) {
        require(fin) { "Control frames must not be fragmented (fin must be true)" }
        require(payloadLength <= 125) {
            "Control frame payload must not exceed 125 bytes, got $payloadLength"
        }
    }

    val maskKey: Int? = if (masked) {
        var key = 0
        repeat(4) { key = (key shl 8) or (source.readByte().toInt() and 0xFF) }
        key
    } else {
        null
    }

    val rawPayload = source.readByteArray(payloadLength.toInt())

    val payload = if (maskKey != null) {
        unmask(rawPayload, maskKey)
    } else {
        rawPayload
    }

    return WsFrame(
        fin = fin,
        rsv1 = rsv1,
        rsv2 = rsv2,
        rsv3 = rsv3,
        opcode = opcode,
        maskKey = maskKey,
        payload = payload,
    )
}

/**
 * Unmasks [data] **in place** (RFC 6455 §5.3) and returns the same array.
 *
 * [data] is the payload [parseFrame] just read from the source via
 * `readByteArray` — a freshly allocated array that nothing else references
 * after this call. XOR-ing it in place avoids allocating a second
 * payload-sized `ByteArray` (and the 4-byte key array) per inbound masked
 * frame. Every client→server data frame is masked (§5.1), so this is the
 * hottest inbound payload allocation after the codec output itself: the
 * previous `ByteArray(data.size) { … }` doubled the per-frame payload
 * allocation on the receive path.
 */
private fun unmask(data: ByteArray, maskKey: Int): ByteArray {
    val k0 = (maskKey shr 24) and 0xFF
    val k1 = (maskKey shr 16) and 0xFF
    val k2 = (maskKey shr 8) and 0xFF
    val k3 = maskKey and 0xFF
    for (i in data.indices) {
        val k = when (i and 3) {
            0 -> k0
            1 -> k1
            2 -> k2
            else -> k3
        }
        data[i] = (data[i].toInt() xor k).toByte()
    }
    return data
}
