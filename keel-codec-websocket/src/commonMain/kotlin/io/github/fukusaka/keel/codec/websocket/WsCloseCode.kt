package io.github.fukusaka.keel.codec.websocket

/**
 * WebSocket close status code as defined in RFC 6455 §7.4.
 *
 * @param code Status code in the range 1000–4999.
 * @throws IllegalArgumentException if [code] is outside 1000–4999.
 */
data class WsCloseCode(val code: Int) {
    init {
        require(code in 1000..4999) { "Invalid close code: $code (must be 1000–4999)" }
    }

    /** True if this code is in the private-use range (4000–4999). */
    val isPrivateUse: Boolean get() = code in 4000..4999

    /** True if this code is reserved and must not be set in a Close frame. */
    val isReserved: Boolean get() = code in RESERVED_CODES

    companion object {
        val NORMAL_CLOSURE = WsCloseCode(1000)
        val GOING_AWAY = WsCloseCode(1001)
        val PROTOCOL_ERROR = WsCloseCode(1002)
        val UNSUPPORTED_DATA = WsCloseCode(1003)
        val NO_STATUS_RCVD = WsCloseCode(1005)
        val ABNORMAL_CLOSURE = WsCloseCode(1006)
        val INVALID_PAYLOAD_DATA = WsCloseCode(1007)
        val POLICY_VIOLATION = WsCloseCode(1008)
        val MESSAGE_TOO_BIG = WsCloseCode(1009)
        val MANDATORY_EXT = WsCloseCode(1010)
        val INTERNAL_ERROR = WsCloseCode(1011)
        val TLS_HANDSHAKE = WsCloseCode(1015)

        private val RESERVED_CODES = setOf(1005, 1006, 1015)
    }
}
