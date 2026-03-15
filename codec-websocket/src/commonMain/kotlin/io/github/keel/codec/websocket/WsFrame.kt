package io.github.keel.codec.websocket

data class WsFrame(
    val fin: Boolean,
    val rsv1: Boolean = false,
    val rsv2: Boolean = false,
    val rsv3: Boolean = false,
    val opcode: WsOpcode,
    val maskKey: Int? = null,
    val payload: ByteArray = ByteArray(0),
) {
    init {
        if (opcode.isControl) {
            require(fin) { "Control frames must not be fragmented (fin must be true)" }
            require(payload.size <= 125) { "Control frame payload must not exceed 125 bytes, got ${payload.size}" }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WsFrame) return false
        return fin == other.fin &&
            rsv1 == other.rsv1 &&
            rsv2 == other.rsv2 &&
            rsv3 == other.rsv3 &&
            opcode == other.opcode &&
            maskKey == other.maskKey &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = fin.hashCode()
        result = 31 * result + rsv1.hashCode()
        result = 31 * result + rsv2.hashCode()
        result = 31 * result + rsv3.hashCode()
        result = 31 * result + opcode.hashCode()
        result = 31 * result + (maskKey ?: 0)
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        fun text(text: String, maskKey: Int? = null, fin: Boolean = true): WsFrame =
            WsFrame(fin = fin, opcode = WsOpcode.TEXT, maskKey = maskKey, payload = text.encodeToByteArray())

        fun binary(data: ByteArray, maskKey: Int? = null, fin: Boolean = true): WsFrame =
            WsFrame(fin = fin, opcode = WsOpcode.BINARY, maskKey = maskKey, payload = data)

        fun continuation(data: ByteArray, maskKey: Int? = null, fin: Boolean = true): WsFrame =
            WsFrame(fin = fin, opcode = WsOpcode.CONTINUATION, maskKey = maskKey, payload = data)

        fun ping(data: ByteArray = ByteArray(0)): WsFrame =
            WsFrame(fin = true, opcode = WsOpcode.PING, payload = data)

        fun pong(data: ByteArray = ByteArray(0)): WsFrame =
            WsFrame(fin = true, opcode = WsOpcode.PONG, payload = data)

        fun close(code: WsCloseCode, reason: String = ""): WsFrame {
            val reasonBytes = reason.encodeToByteArray()
            val payload = ByteArray(2 + reasonBytes.size)
            payload[0] = (code.code shr 8).toByte()
            payload[1] = (code.code and 0xFF).toByte()
            reasonBytes.copyInto(payload, 2)
            return WsFrame(fin = true, opcode = WsOpcode.CLOSE, payload = payload)
        }

        fun close(): WsFrame =
            WsFrame(fin = true, opcode = WsOpcode.CLOSE, payload = ByteArray(0))
    }
}
