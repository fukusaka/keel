package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig
import kotlin.concurrent.AtomicReference
import kotlinx.cinterop.ExperimentalForeignApi
import mbedtls.psa_crypto_init

/**
 * [TlsCodecFactory] implementation for Mbed TLS 4.x.
 *
 * Shares one PSA Crypto init + per-([isServer], [TlsConfig])
 * [MbedTlsServerSession] (cert chain, private key,
 * `mbedtls_ssl_config`) across every codec the factory produces;
 * per-connection [MbedTlsCodec] instances only allocate
 * `mbedtls_ssl_context` + BIO context and attach to the shared
 * config via `mbedtls_ssl_setup`. This avoids the pre-K53
 * per-codec `psa_crypto_init` + `x509_crt_parse` race that crashed
 * the multi-worker pipeline-http-epoll path under load.
 *
 * **Concurrency**: `createServerCodec` is safe to call from multiple
 * threads. The internal session cache is an [AtomicReference] over
 * an immutable map updated by CAS-loop; lookups are wait-free and
 * the initial session-creation race is bounded (one redundant init
 * per losing thread, immediately closed).
 *
 * **Lifetime**: the factory owns its sessions. Call [close] to free
 * all cached cert / key / config resources; doing so while live
 * codecs reference a session leads to use-after-free.
 */
@OptIn(ExperimentalForeignApi::class)
class MbedTlsCodecFactory : TlsCodecFactory {

    init {
        // One-time PSA Crypto init per factory. Mbed TLS 4.x requires
        // this before any other PSA / TLS operation; calling it once
        // up front (rather than per-codec) avoids racing against
        // concurrent createServerCodec invocations on the homebrew /
        // distro builds that disable MBEDTLS_THREADING_C — see K53.
        val ret = psa_crypto_init().toInt()
        check(ret == 0) { "psa_crypto_init failed: $ret" }
    }

    private val sessions = AtomicReference<Map<Pair<Boolean, TlsConfig>, MbedTlsServerSession>>(emptyMap())

    override fun createServerCodec(config: TlsConfig): TlsCodec =
        MbedTlsCodec(getOrCreateSession(isServer = true, config = config))

    override fun createClientCodec(config: TlsConfig): TlsCodec =
        MbedTlsCodec(getOrCreateSession(isServer = false, config = config))

    private fun getOrCreateSession(isServer: Boolean, config: TlsConfig): MbedTlsServerSession {
        val key = isServer to config
        while (true) {
            val current = sessions.value
            current[key]?.let { return it }
            val newSession = MbedTlsServerSession(isServer, config)
            val updated = current + (key to newSession)
            if (sessions.compareAndSet(current, updated)) {
                return newSession
            }
            // Lost the CAS race against another thread that installed
            // a session for the same or a different key in parallel.
            // Drop our half-built one and retry the lookup.
            newSession.close()
        }
    }

    override fun close() {
        while (true) {
            val current = sessions.value
            if (sessions.compareAndSet(current, emptyMap())) {
                current.values.forEach { it.close() }
                return
            }
        }
    }
}
