package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.SocketAddress
import io.github.fukusaka.keel.core.UnixSocketAddress
import io.ktor.http.HttpMethod
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Contract test for [KeelConnectionPoint], the pure derivation from a keel
 * [SocketAddress] + HTTP request head to Ktor's [io.ktor.http.RequestConnectionPoint].
 * A regression here reports the wrong host / port / scheme to application code
 * (`call.request.local` / `.origin`), so the derivation and its fallback chains
 * are pinned directly.
 */
class KeelConnectionPointTest {

    private fun point(
        local: SocketAddress? = null,
        remote: SocketAddress? = null,
        host: String? = null,
        scheme: String = "http",
        uri: String = "/",
    ) = KeelConnectionPoint(
        localAddr = local,
        remoteAddr = remote,
        version = "HTTP/1.1",
        uri = uri,
        hostHeaderValue = host,
        method = HttpMethod.Get,
        scheme = scheme,
    )

    @Test
    fun `local host and port come from the local socket address`() {
        val p = point(local = InetSocketAddress("10.0.0.5", 8080))
        assertEquals("10.0.0.5", p.localHost)
        assertEquals("10.0.0.5", p.localAddress)
        assertEquals(8080, p.localPort)
    }

    @Test
    fun `local port and host fall back to scheme default and localhost when the address is absent`() {
        val http = point(local = null, scheme = "http")
        assertEquals(80, http.localPort)
        assertEquals("localhost", http.localHost)

        val https = point(local = null, scheme = "https")
        assertEquals(443, https.localPort)
    }

    @Test
    fun `server host and port are taken from the Host header`() {
        val p = point(local = InetSocketAddress("10.0.0.5", 8080), host = "example.com:9090")
        assertEquals("example.com", p.serverHost)
        assertEquals(9090, p.serverPort)
    }

    @Test
    fun `server port falls back to the scheme default when the Host header omits the port`() {
        val p = point(local = InetSocketAddress("10.0.0.5", 8080), host = "example.com", scheme = "http")
        assertEquals("example.com", p.serverHost)
        assertEquals(80, p.serverPort)
    }

    @Test
    fun `server host and port fall back to the local values when there is no Host header`() {
        val p = point(local = InetSocketAddress("10.0.0.5", 8080), host = null)
        assertEquals("10.0.0.5", p.serverHost)
        assertEquals(8080, p.serverPort)
    }

    @Test
    fun `remote fields come from the remote socket address`() {
        val p = point(remote = InetSocketAddress("192.168.1.7", 51000))
        assertEquals("192.168.1.7", p.remoteHost)
        assertEquals("192.168.1.7", p.remoteAddress)
        assertEquals(51000, p.remotePort)
    }

    @Test
    fun `remote fields report unknown and zero when the remote address is absent`() {
        val p = point(remote = null)
        assertEquals("unknown", p.remoteHost)
        assertEquals("unknown", p.remoteAddress)
        assertEquals(0, p.remotePort)
    }

    @Test
    fun `a unix socket address exposes its path as the host and the scheme default port`() {
        val p = point(local = UnixSocketAddress("/tmp/keel.sock"), scheme = "http")
        assertEquals("/tmp/keel.sock", p.localHost)
        // A UDS has no port, so localPort falls back to the scheme default.
        assertEquals(80, p.localPort)
    }
}
