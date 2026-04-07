package io.github.fukusaka.keel.tls.jsse

import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsVerifyMode
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for [JsseTlsCodecFactory] error handling.
 *
 * Mirrors [MbedTlsCodecTest][io.github.fukusaka.keel.tls.mbedtls.MbedTlsCodecTest]
 * to ensure consistent error behavior across TLS backends.
 */
class JsseTlsCodecTest {

    @Test
    fun `invalid PEM certificate throws exception`() {
        val config = TlsConfig(
            certificates = TlsCertificateSource.Pem(
                certificatePem = "NOT A VALID PEM\n",
                privateKeyPem = TestCertificates.SERVER_KEY,
            ),
            verifyMode = TlsVerifyMode.NONE,
        )
        assertFailsWith<Exception> {
            JsseTlsCodecFactory().createServerCodec(config)
        }
    }

    @Test
    fun `invalid PEM private key throws exception`() {
        val config = TlsConfig(
            certificates = TlsCertificateSource.Pem(
                certificatePem = TestCertificates.SERVER_CERT,
                privateKeyPem = "NOT A VALID KEY\n",
            ),
            verifyMode = TlsVerifyMode.NONE,
        )
        assertFailsWith<Exception> {
            JsseTlsCodecFactory().createServerCodec(config)
        }
    }
}
