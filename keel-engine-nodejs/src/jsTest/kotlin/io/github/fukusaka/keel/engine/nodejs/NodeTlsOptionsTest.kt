package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.TlsVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Compose-level smoke coverage for [NodeTlsOptions.build] — pins that
 * every server-relevant axis of [TlsConfig] lands in the expected
 * property of the `tls.createServer(...)` options object. Handshake-level
 * Red-Green (that these options actually reject / accept peers on the
 * wire) lives in [NodeListenerTlsIntegrationTest].
 */
class NodeTlsOptionsTest {

    private val serverCerts = TlsCertificateSource.Pem(
        NodeTestCertificates.SERVER_CERT,
        NodeTestCertificates.SERVER_KEY,
    )

    // --- axis: certificates + version range default ---

    @Test
    fun `default config produces key and cert and default version range`() {
        val options = NodeTlsOptions.build(TlsConfig(certificates = serverCerts))
        assertEquals(NodeTestCertificates.SERVER_KEY, options.key)
        assertEquals(NodeTestCertificates.SERVER_CERT, options.cert)
        assertEquals("TLSv1.2", options.minVersion, "TlsConfig default minVersion is TLS 1.2")
        assertEquals("TLSv1.3", options.maxVersion, "when maxVersion is null the ceiling is TLS 1.3")
    }

    // --- axis: minVersion / maxVersion ---

    @Test
    fun `minVersion TLS1_3 lands in options`() {
        val options = NodeTlsOptions.build(
            TlsConfig(certificates = serverCerts, minVersion = TlsVersion.TLS1_3),
        )
        assertEquals("TLSv1.3", options.minVersion)
    }

    @Test
    fun `maxVersion TLS1_2 lands in options`() {
        val options = NodeTlsOptions.build(
            TlsConfig(certificates = serverCerts, maxVersion = TlsVersion.TLS1_2),
        )
        assertEquals("TLSv1.2", options.maxVersion)
    }

    // --- axis: alpnProtocols ---

    @Test
    fun `alpnProtocols lands in ALPNProtocols array`() {
        val options = NodeTlsOptions.build(
            TlsConfig(certificates = serverCerts, alpnProtocols = listOf("h2", "http/1.1")),
        )
        val alpn = options.ALPNProtocols
        assertEquals("h2", alpn[0])
        assertEquals("http/1.1", alpn[1])
    }

    @Test
    fun `null alpnProtocols leaves ALPNProtocols unset`() {
        val options = NodeTlsOptions.build(TlsConfig(certificates = serverCerts))
        // `dynamic` returns `undefined` for missing properties, which is
        // Kotlin-null after the dynamic bridge.
        assertNull(options.ALPNProtocols)
    }

    // --- axis: verifyMode (mTLS mapping) ---

    @Test
    fun `verifyMode NONE disables client cert request`() {
        val options = NodeTlsOptions.build(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE),
        )
        assertEquals(false, options.requestCert)
        assertEquals(false, options.rejectUnauthorized)
    }

    @Test
    fun `verifyMode PEER requests but does not reject on failure`() {
        // The Node backend can express the true PEER middle ground
        // (unlike NW where sec_protocol_options_set_peer_authentication_optional
        // is API_UNAVAILABLE and PEER has to be mapped to REQUIRED).
        val options = NodeTlsOptions.build(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.PEER),
        )
        assertEquals(true, options.requestCert)
        assertEquals(false, options.rejectUnauthorized)
    }

    @Test
    fun `verifyMode REQUIRED requests and rejects on failure`() {
        val options = NodeTlsOptions.build(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.REQUIRED),
        )
        assertEquals(true, options.requestCert)
        assertEquals(true, options.rejectUnauthorized)
    }

    // --- axis: trustAnchors ---

    @Test
    fun `trustAnchors Pem lands in options ca`() {
        val options = NodeTlsOptions.build(
            TlsConfig(
                certificates = serverCerts,
                verifyMode = TlsVerifyMode.REQUIRED,
                trustAnchors = TlsTrustSource.Pem(NodeTestCertificates.CLIENT_CA_CERT),
            ),
        )
        assertEquals(NodeTestCertificates.CLIENT_CA_CERT, options.ca)
    }

    @Test
    fun `null trustAnchors leaves ca unset so Node uses its built-in CA set`() {
        val options = NodeTlsOptions.build(TlsConfig(certificates = serverCerts))
        assertNull(options.ca)
    }

    // --- failure ---

    @Test
    fun `missing certificates throws`() {
        assertFailsWith<IllegalArgumentException> {
            NodeTlsOptions.build(TlsConfig(certificates = null))
        }
    }
}
