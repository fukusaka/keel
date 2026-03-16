package io.github.keel.codec.websocket

/**
 * WebSocket frame opcode as defined in RFC 6455 §5.2.
 *
 * Data frames: [CONTINUATION], [TEXT], [BINARY].
 * Control frames: [CLOSE], [PING], [PONG].
 */
enum class WsOpcode(val code: Int) {
    CONTINUATION(0x0),
    TEXT(0x1),
    BINARY(0x2),
    CLOSE(0x8),
    PING(0x9),
    PONG(0xA);

    /** True if this is a control opcode (0x8–0xF). */
    val isControl: Boolean get() = code in 0x8..0xF

    /** True if this is a data opcode (0x0–0x2). */
    val isData: Boolean get() = code in 0x0..0x2

    companion object {
        /**
         * Returns the [WsOpcode] for [code].
         *
         * @throws IllegalArgumentException for unknown opcodes.
         */
        fun fromCode(code: Int): WsOpcode =
            entries.firstOrNull { it.code == code }
                ?: throw IllegalArgumentException("Unknown WebSocket opcode: 0x${code.toString(16)}")
    }
}
