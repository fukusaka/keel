package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.concurrent.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for the PSA threading race — `pipeline-http-epoll × mbedtls` core dump
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

    @Test
    fun `concurrent createServerCodec across many distinct TlsConfigs does not corrupt PSA Crypto state`() = runBlocking {
        // Simulates a multi-connector server bind (each connector with
        // its own TlsConfig — e.g. per-vhost cert + verifyMode +
        // trustAnchors variations) accepting first-burst connections
        // concurrently across worker threads. Distinct cache keys
        // force each worker through MbedTlsServerSession construction
        // simultaneously rather than the cheap cache-hit path; without
        // the construct-side mutex, two `mbedtls_x509_crt_parse` /
        // `mbedtls_pk_parse_key` invocations race in PSA Crypto's
        // global key store and abort the process.
        val factory = MbedTlsCodecFactory()
        try {
            withTimeout(60.seconds) {
                val configs = (0 until DISTINCT_CONFIGS).map { i ->
                    TlsConfig(
                        certificates = TlsCertificateSource.Pem(
                            certificatePem = TestCertificates.SERVER_CERT,
                            privateKeyPem = TestCertificates.SERVER_KEY,
                        ),
                        // Vary verifyMode + serverName so each config
                        // is a distinct data-class equality key and
                        // collides on neither the cache nor the
                        // first-construction race.
                        verifyMode = if (i % 2 == 0) TlsVerifyMode.NONE else TlsVerifyMode.REQUIRED,
                        trustAnchors = TlsTrustSource.InsecureTrustAll,
                        serverName = "vhost-$i.example.com",
                    )
                }
                val successCount = (0 until CONCURRENCY).map { worker ->
                    async(Dispatchers.Default) {
                        var ok = 0
                        repeat(ITERATIONS) { iter ->
                            // Each worker cycles through all distinct
                            // configs so the multi-config construction
                            // race surfaces in the first iteration of
                            // every worker (worst case for the
                            // construct-side mutex).
                            val config = configs[(worker + iter) % DISTINCT_CONFIGS]
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

    @Test
    fun `close races safely against concurrent createServerCodec`() = runBlocking {
        // Spawns CLOSE_RACE_WORKERS workers in an unbounded loop
        // hammering createServerCodec; a separate coroutine fires
        // close() once the workers have demonstrably begun
        // (`okCount >= READY_THRESHOLD`). Workers exit when they see
        // their first IllegalStateException, so the test terminates
        // regardless of host scheduling speed and the race window is
        // guaranteed to be active when close() fires.
        //
        // Pre the closed-flag defence, close()'s pthread_mutex_destroy
        // + arena.clear() would touch a held mutex / freed storage
        // and segfault the test process. With the defence every
        // losing caller surfaces IllegalStateException, every winning
        // caller gets a real codec, and the process stays alive.
        val config = TlsConfig(
            certificates = TlsCertificateSource.Pem(
                certificatePem = TestCertificates.SERVER_CERT,
                privateKeyPem = TestCertificates.SERVER_KEY,
            ),
            verifyMode = TlsVerifyMode.NONE,
        )
        val factory = MbedTlsCodecFactory()
        // Warm the cache so most workers hit the wait-free fast path
        // and a non-trivial number race against close() with the
        // closed-flag check post-acquire.
        factory.createServerCodec(config).close()

        val okCount = AtomicInt(0)
        val rejectedCount = AtomicInt(0)

        withTimeout(30.seconds) {
            val workers = (0 until CLOSE_RACE_WORKERS).map {
                async(Dispatchers.Default) {
                    while (true) {
                        try {
                            val codec = factory.createServerCodec(config)
                            codec.close()
                            okCount.incrementAndGet()
                        } catch (_: IllegalStateException) {
                            rejectedCount.incrementAndGet()
                            return@async
                        }
                        // Yield so the trigger coroutine (which fires
                        // factory.close()) is never starved by tight
                        // worker loops on a small-thread-pool host
                        // (CI Ubuntu runner = 4 cores). Without this,
                        // the workers monopolise Dispatchers.Default
                        // and the test hangs to the job timeout.
                        yield()
                    }
                    @Suppress("UNREACHABLE_CODE")
                    Unit
                }
            }
            // Trigger close() only after workers have demonstrably
            // begun, so the race window is non-empty no matter how
            // fast the host is. Spin-yield is fine — runBlocking
            // dispatcher owns this coroutine, async workers run on
            // Dispatchers.Default.
            launch(Dispatchers.Default) {
                while (okCount.value < READY_THRESHOLD) delay(1)
                factory.close()
            }
            workers.awaitAll()
        }

        val ok = okCount.value
        val rejected = rejectedCount.value
        // We don't pin exact counts (depends on scheduling) but both
        // must be > 0 — ok proves workers actually ran before close,
        // rejected proves close() turned them away cleanly via
        // IllegalStateException rather than process abort.
        assertTrue(ok >= READY_THRESHOLD, "expected ok >= $READY_THRESHOLD, got $ok")
        assertTrue(rejected > 0, "expected some IllegalStateException after close, got $rejected")
        assertEquals(CLOSE_RACE_WORKERS, rejected, "each worker should bail exactly once")

        // close() idempotency — no second drain, no segfault.
        factory.close()
    }

    companion object {
        // Tuned to comfortably trip the pre-fix race on a modern multi-core
        // host (16+ cores) within a few seconds. Lower bounds chosen to
        // also catch the race on smaller hosts (4-core CI runners) without
        // exceeding the test wall-clock budget.
        private const val CONCURRENCY = 8
        private const val ITERATIONS = 50

        // Bigger than CONCURRENCY so the multi-config test always
        // hits the first-construction path on at least
        // (DISTINCT_CONFIGS - CONCURRENCY) iterations even after the
        // cache populates.
        private const val DISTINCT_CONFIGS = 16

        // Worker count + ok-count threshold gate close() so the race
        // window is guaranteed open at trigger time on any host.
        private const val CLOSE_RACE_WORKERS = 8
        private const val READY_THRESHOLD = 200
    }
}
