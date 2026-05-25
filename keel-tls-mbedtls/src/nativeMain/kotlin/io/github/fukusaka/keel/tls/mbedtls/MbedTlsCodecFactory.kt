package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig
import kotlin.concurrent.AtomicReference
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import mbedtls.psa_crypto_init
import platform.posix.pthread_mutex_destroy
import platform.posix.pthread_mutex_init
import platform.posix.pthread_mutex_lock
import platform.posix.pthread_mutex_t
import platform.posix.pthread_mutex_unlock

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
 * **Concurrency**:
 *
 * - The session cache is an [AtomicReference] over an immutable map,
 *   so the steady-state lookup path (every connection after the
 *   first per-`(isServer, TlsConfig)` key) is wait-free.
 * - Session **construction** (`mbedtls_x509_crt_parse` +
 *   `mbedtls_pk_parse_key` + `mbedtls_ssl_config_defaults` +
 *   `mbedtls_ssl_conf_own_cert`) is serialised under
 *   [constructMutex]. Mbed TLS 4.x's PSA Crypto subsystem is not
 *   thread-safe in homebrew / distro builds (`MBEDTLS_THREADING_C`
 *   disabled); without serialisation, two threads constructing
 *   sessions in parallel — same-key on the first burst of accepts,
 *   or different-key on a multi-connector server — race in PSA's
 *   global key store and crash with `MBEDTLS_ERR_PK_INVALID_PUBKEY`.
 *   The mutex is uncontended once each `(isServer, TlsConfig)` has
 *   its session, so the overhead is bounded to first-burst startup.
 *
 * **Lifetime**: the factory owns its sessions and the construction
 * mutex. Call [close] to free all cached cert / key / config
 * resources + destroy the mutex; doing so while live codecs
 * reference a session leads to use-after-free.
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

    private val arena = Arena()
    private val constructMutex = arena.alloc<pthread_mutex_t>().apply {
        pthread_mutex_init(ptr, null)
    }

    override fun createServerCodec(config: TlsConfig): TlsCodec =
        MbedTlsCodec(getOrCreateSession(isServer = true, config = config))

    override fun createClientCodec(config: TlsConfig): TlsCodec =
        MbedTlsCodec(getOrCreateSession(isServer = false, config = config))

    private fun getOrCreateSession(isServer: Boolean, config: TlsConfig): MbedTlsServerSession {
        val key = isServer to config
        // Fast path: wait-free cache hit. Always check before taking the
        // mutex so steady-state load (cache populated) skips the lock.
        sessions.value[key]?.let { return it }

        pthread_mutex_lock(constructMutex.ptr)
        try {
            // Double-check under the lock — another thread may have
            // constructed and installed our session while we were
            // waiting on the mutex.
            sessions.value[key]?.let { return it }

            // Serialised construction. PSA Crypto's key store is not
            // safe under concurrent `mbedtls_x509_crt_parse` /
            // `mbedtls_pk_parse_key` on independent struct pointers
            // when MBEDTLS_THREADING_C is disabled at the C build;
            // holding the mutex across construction prevents two
            // threads (same-key first burst, or distinct-key
            // multi-connector startup) from PSA-racing.
            val newSession = MbedTlsServerSession(isServer, config)
            while (true) {
                val current = sessions.value
                val updated = current + (key to newSession)
                if (sessions.compareAndSet(current, updated)) return newSession
                // The CAS can only lose to a concurrent close() draining
                // the map (no other writer holds the mutex). Retry the
                // CAS so the new entry lands on the post-close map.
            }
        } finally {
            pthread_mutex_unlock(constructMutex.ptr)
        }
    }

    override fun close() {
        // Drain the cache atomically and free each session. The mutex
        // is left intact across this drain because in-flight
        // getOrCreateSession callers may still be waiting on it; the
        // arena destruction below frees the mutex after they unwind.
        val drained = sessions.getAndSet(emptyMap())
        drained.values.forEach { it.close() }

        pthread_mutex_destroy(constructMutex.ptr)
        arena.clear()
    }
}
