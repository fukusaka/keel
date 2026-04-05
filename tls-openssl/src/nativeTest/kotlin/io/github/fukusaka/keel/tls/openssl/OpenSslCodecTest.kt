package io.github.fukusaka.keel.tls.openssl

import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsVerifyMode
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Unit tests for [OpenSslCodecFactory] error handling.
 *
 * Mirrors [MbedTlsCodecTest] to ensure consistent error behavior
 * across TLS backends.
 */
class OpenSslCodecTest {

    @Test
    fun `invalid PEM certificate throws TlsException`() {
        val config = TlsConfig(
            certificates = TlsCertificateSource.Pem(
                certificatePem = "NOT A VALID PEM\n",
                privateKeyPem = TestCertificates.SERVER_KEY,
            ),
            verifyMode = TlsVerifyMode.NONE,
        )
        assertFailsWith<TlsException> {
            OpenSslCodecFactory().createServerCodec(config)
        }
    }

    @Test
    fun `invalid PEM private key throws TlsException`() {
        val config = TlsConfig(
            certificates = TlsCertificateSource.Pem(
                certificatePem = TestCertificates.SERVER_CERT,
                privateKeyPem = "NOT A VALID KEY\n",
            ),
            verifyMode = TlsVerifyMode.NONE,
        )
        assertFailsWith<TlsException> {
            OpenSslCodecFactory().createServerCodec(config)
        }
    }
}
