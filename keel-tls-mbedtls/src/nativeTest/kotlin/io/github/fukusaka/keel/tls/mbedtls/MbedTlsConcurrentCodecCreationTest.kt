package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsVerifyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for K53 — `pipeline-http-epoll × mbedtls` core dump
 * under sustained load.
 *
 * **Root cause** (pre-fix): [MbedTlsCodecFactory.createServerCodec]
 * constructs a new [MbedTlsCodec] per connection, and each codec's
 * `init` block calls `psa_crypto_init()` + `mbedtls_x509_crt_parse` +
 * `mbedtls_pk_parse_key` + `mbedtls_ssl_config_init` independently.
 * Mbed TLS 4.x's PSA Crypto subsystem is **not thread-safe** unless
 * `MBEDTLS_THREADING_C` is enabled at build time (which the homebrew
 * / Linux distro packages do not enable). Concurrent invocations of
 * `psa_crypto_init` + PEM/key parsing race in the PSA global key
 * store and corrupt the parser state, surfacing as
 * `x509_crt_parse failed: MBEDTLS_ERR_PK_INVALID_PUBKEY (0x3B00)`
 * — the parsed cert's public key import via PSA fails because
 * concurrent key slot assignment collides.
 *
 * **Fix**: hoist `psa_crypto_init` + cert / key / config initialization
 * to [MbedTlsCodecFactory] (one shared init per factory instance);
 * per-connection [MbedTlsCodec] only allocates `mbedtls_ssl_context` +
 * BIO context and attaches to the shared config via `mbedtls_ssl_setup`.
 *
 * **Red-Green**: pre-fix this test reproduces the crash (TlsException
 * with 0x3B00 from at least one of the concurrent codec creations);
 * post-fix all [CONCURRENCY] × [ITERATIONS] codecs construct cleanly.
 */
class MbedTlsConcurrentCodecCreationTest {

    @Test
    fun `concurrent createServerCodec across many coroutines does not corrupt PSA Crypto state`() = runBlocking {
        val config = TlsConfig(
            certificates = TlsCertificateSource.Pem(
                certificatePem = TestCertificates.SERVER_CERT,
                privateKeyPem = TestCertificates.SERVER_KEY,
            ),
            verifyMode = TlsVerifyMode.NONE,
        )
        val factory = MbedTlsCodecFactory()
        try {
            withTimeout(60.seconds) {
                val successCount = (0 until CONCURRENCY).map {
                    async(Dispatchers.Default) {
                        var ok = 0
                        repeat(ITERATIONS) {
                            // Create + immediately close — exercises the
                            // init path (psa_crypto_init + x509_crt_parse +
                            // pk_parse_key + ssl_config_defaults +
                            // ssl_conf_own_cert + ssl_setup) under
                            // concurrent pressure.
                            val codec = factory.createServerCodec(config)
                            codec.close()
                            ok++
                        }
                        ok
                    }
                }.awaitAll().sum()
                assertEquals(CONCURRENCY * ITERATIONS, successCount)
            }
        } finally {
            factory.close()
        }
    }

    companion object {
        // Tuned to comfortably trip the pre-fix race on a modern multi-core
        // host (16+ cores) within a few seconds. Lower bounds chosen to
        // also catch the race on smaller hosts (4-core CI runners) without
        // exceeding the test wall-clock budget.
        private const val CONCURRENCY = 8
        private const val ITERATIONS = 50
    }
}
