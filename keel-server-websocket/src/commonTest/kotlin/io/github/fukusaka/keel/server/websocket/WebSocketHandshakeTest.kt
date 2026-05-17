package io.github.fukusaka.keel.server.websocket

import io.github.fukusaka.keel.codec.http.HttpHeaders
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Unit tests for [HttpHeaders.isWebSocketUpgrade] handshake detection. */
class WebSocketHandshakeTest {

    private fun upgradeHeaders(
        upgrade: String? = "websocket",
        connection: String? = "Upgrade",
        version: String? = "13",
        key: String? = VALID_KEY,
    ): HttpHeaders {
        val headers = HttpHeaders()
        if (upgrade != null) headers["Upgrade"] = upgrade
        if (connection != null) headers["Connection"] = connection
        if (version != null) headers["Sec-WebSocket-Version"] = version
        if (key != null) headers["Sec-WebSocket-Key"] = key
        return headers
    }

    @Test
    fun `valid handshake is detected`() {
        assertTrue(upgradeHeaders().isWebSocketUpgrade())
    }

    @Test
    fun `Upgrade header matched case-insensitively`() {
        assertTrue(upgradeHeaders(upgrade = "WebSocket").isWebSocketUpgrade())
        assertTrue(upgradeHeaders(upgrade = "WEBSOCKET").isWebSocketUpgrade())
    }

    @Test
    fun `Connection header tolerates comma-separated values`() {
        // Browsers often send "keep-alive, Upgrade" — RFC 6455 §4.1
        // step 2 says the field MUST contain Upgrade, not equal it.
        assertTrue(upgradeHeaders(connection = "keep-alive, Upgrade").isWebSocketUpgrade())
        assertTrue(upgradeHeaders(connection = "Upgrade, keep-alive").isWebSocketUpgrade())
    }

    @Test
    fun `non-websocket Upgrade is rejected`() {
        assertFalse(upgradeHeaders(upgrade = "h2c").isWebSocketUpgrade())
    }

    @Test
    fun `missing Upgrade header is rejected`() {
        assertFalse(upgradeHeaders(upgrade = null).isWebSocketUpgrade())
    }

    @Test
    fun `missing Connection header is rejected`() {
        assertFalse(upgradeHeaders(connection = null).isWebSocketUpgrade())
    }

    @Test
    fun `Connection without Upgrade token is rejected`() {
        assertFalse(upgradeHeaders(connection = "keep-alive").isWebSocketUpgrade())
    }

    @Test
    fun `wrong protocol version is rejected`() {
        assertFalse(upgradeHeaders(version = "8").isWebSocketUpgrade())
        assertFalse(upgradeHeaders(version = null).isWebSocketUpgrade())
    }

    @Test
    fun `invalid Sec-WebSocket-Key length is rejected`() {
        // Valid Base64 but decodes to a non-16-byte payload.
        assertFalse(upgradeHeaders(key = "AAAA").isWebSocketUpgrade())
    }

    @Test
    fun `missing Sec-WebSocket-Key is rejected`() {
        assertFalse(upgradeHeaders(key = null).isWebSocketUpgrade())
    }

    private companion object {
        // 16-byte nonce, Base64-encoded — RFC 6455 §1.3 example.
        const val VALID_KEY = "dGhlIHNhbXBsZSBub25jZQ=="
    }
}
