package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.codec.http.HttpHeaderName
import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.websocket.validateClientKey

private const val SEC_WEBSOCKET_KEY = "Sec-WebSocket-Key"
private const val SEC_WEBSOCKET_VERSION = "Sec-WebSocket-Version"
private const val WEBSOCKET_VERSION_13 = "13"

/**
 * True when [this] is a valid RFC 6455 §4.1 client handshake —
 * `Upgrade: websocket`, `Connection: Upgrade`, a valid 16-byte
 * `Sec-WebSocket-Key`, and `Sec-WebSocket-Version: 13`.
 *
 * Header matching is case-insensitive; the `Connection` header is
 * tolerated as a comma-separated token list (a proxy may add `keep-alive`).
 */
public fun HttpHeaders.isWebSocketUpgrade(): Boolean {
    if (!this[HttpHeaderName.UPGRADE].equalsIgnoreCase("websocket")) return false
    val connection = this[HttpHeaderName.CONNECTION] ?: return false
    if (connection.split(',').none { it.trim().equalsIgnoreCase("upgrade") }) return false
    if (this[SEC_WEBSOCKET_VERSION] != WEBSOCKET_VERSION_13) return false
    val key = this[SEC_WEBSOCKET_KEY] ?: return false
    return validateClientKey(key)
}

/** Case-insensitive equality tolerant of a null receiver (an absent header). */
private fun String?.equalsIgnoreCase(other: String): Boolean =
    this != null && this.equals(other, ignoreCase = true)
