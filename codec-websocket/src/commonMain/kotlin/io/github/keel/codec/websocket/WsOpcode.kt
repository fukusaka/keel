package io.github.keel.codec.websocket

enum class WsOpcode(val code: Int) {
    CONTINUATION(0x0),
    TEXT(0x1),
    BINARY(0x2),
    CLOSE(0x8),
    PING(0x9),
    PONG(0xA);

    val isControl: Boolean get() = code in 0x8..0xF
    val isData: Boolean get() = code in 0x0..0x2

    companion object {
        fun fromCode(code: Int): WsOpcode =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown WebSocket opcode: 0x${code.toString(16)}")
    }
}
