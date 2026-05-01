package io.github.fukusaka.keel.server.ktor.websocket

import io.github.fukusaka.keel.codec.http.HttpHeaders
import io.github.fukusaka.keel.codec.http.HttpMethod
import io.github.fukusaka.keel.codec.http.HttpRequestHead
import io.github.fukusaka.keel.codec.http.HttpVersion
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WsUpgradeDetectionTest {

    private fun upgradeRequest(
        upgrade: String? = "websocket",
        connection: String? = "Upgrade",
        version: String? = "13",
        key: String? = VALID_KEY,
    ): HttpRequestHead {
        val headers = HttpHeaders()
        if (upgrade != null) headers["Upgrade"] = upgrade
        if (connection != null) headers["Connection"] = connection
        if (version != null) headers["Sec-WebSocket-Version"] = version
        if (key != null) headers["Sec-WebSocket-Key"] = key
        return HttpRequestHead(method = HttpMethod.GET, uri = "/echo", version = HttpVersion.HTTP_1_1, headers = headers)
    }

    @Test
    fun `valid handshake is detected`() {
        assertTrue(upgradeRequest().isWebSocketUpgrade())
    }

    @Test
    fun `Upgrade header matched case-insensitively`() {
        assertTrue(upgradeRequest(upgrade = "WebSocket").isWebSocketUpgrade())
        assertTrue(upgradeRequest(upgrade = "WEBSOCKET").isWebSocketUpgrade())
    }

    @Test
    fun `Connection header tolerates comma-separated values`() {
        // Browsers often send "keep-alive, Upgrade" — RFC 6455 §4.1
        // step 2 says the field MUST contain Upgrade, not equal it.
        assertTrue(upgradeRequest(connection = "keep-alive, Upgrade").isWebSocketUpgrade())
        assertTrue(upgradeRequest(connection = "Upgrade, keep-alive").isWebSocketUpgrade())
    }

    @Test
    fun `non-websocket Upgrade is rejected`() {
        assertFalse(upgradeRequest(upgrade = "h2c").isWebSocketUpgrade())
    }

    @Test
    fun `missing Upgrade header is rejected`() {
        assertFalse(upgradeRequest(upgrade = null).isWebSocketUpgrade())
    }

    @Test
    fun `missing Connection header is rejected`() {
        assertFalse(upgradeRequest(connection = null).isWebSocketUpgrade())
    }

    @Test
    fun `Connection without Upgrade token is rejected`() {
        assertFalse(upgradeRequest(connection = "keep-alive").isWebSocketUpgrade())
    }

    @Test
    fun `wrong protocol version is rejected`() {
        assertFalse(upgradeRequest(version = "8").isWebSocketUpgrade())
        assertFalse(upgradeRequest(version = null).isWebSocketUpgrade())
    }

    @Test
    fun `invalid Sec-WebSocket-Key length is rejected`() {
        // Valid Base64 but decodes to a non-16-byte payload.
        assertFalse(upgradeRequest(key = "AAAA").isWebSocketUpgrade())
    }

    @Test
    fun `missing Sec-WebSocket-Key is rejected`() {
        assertFalse(upgradeRequest(key = null).isWebSocketUpgrade())
    }

    private companion object {
        // 16-byte nonce, Base64-encoded — RFC 6455 §1.3 example.
        const val VALID_KEY = "dGhlIHNhbXBsZSBub25jZQ=="
    }
}
