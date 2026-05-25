package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlinx.cinterop.Arena
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.ptr
import mbedtls.psa_crypto_init
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
 * - [close] is **race-safe** against concurrent
 *   [createServerCodec] / [createClientCodec]: the [closed] flag
 *   is checked before mutex acquisition, a re-check after
 *   acquisition handles the narrow window where [close] flagged
 *   while a caller waited on the mutex. Callers that lose the race
 *   receive [IllegalStateException], never silent UB. The mutex
 *   itself is intentionally **not** destroyed by [close] and the
 *   backing [arena] is left uncleared — there is no POSIX-compliant
 *   way to signal "all waiters have drained" before
 *   `pthread_mutex_destroy`, so we let the OS reclaim the small
 *   per-factory allocation at process exit instead of risking
 *   destroy-while-held UB. Mid-process factory churn therefore
 *   leaks one `pthread_mutex_t` + Arena bookkeeping per `close()`;
 *   acceptable for a per-server-lifetime factory.
 *
 * **Lifetime — what [close] protects**:
 *
 * - **Concurrent [createServerCodec] / [createClientCodec] during
 *   [close]**: fails fast with [IllegalStateException], no UB.
 * - **Live codec still using a session at [close]**: the session
 *   itself is reference-counted (see [MbedTlsServerSession]) — the
 *   factory holds one ref per cached session, each codec holds
 *   one. [close] drops the factory refs; if codecs are still
 *   live, the underlying `mbedtls_ssl_config` / cert / key stay
 *   alive until the last codec closes. Either ordering (factory
 *   close first, or codec close first) is safe.
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

    // 0 = open, 1 = closed. AtomicInt because K/N's stable
    // AtomicReference family does not expose a Boolean specialisation;
    // the integer load is identical in cost.
    private val closed = AtomicInt(0)

    private val arena = Arena()
    private val constructMutex = arena.alloc<pthread_mutex_t>().apply {
        pthread_mutex_init(ptr, null)
    }

    override fun createServerCodec(config: TlsConfig): TlsCodec =
        MbedTlsCodec(acquireSession(isServer = true, config = config))

    override fun createClientCodec(config: TlsConfig): TlsCodec =
        MbedTlsCodec(acquireSession(isServer = false, config = config))

    /**
     * Returns a [MbedTlsServerSession] with **one extra reference
     * pre-acquired** for the caller — the [MbedTlsCodec] constructor
     * inherits this ref and balances it in its `close()`. Doing the
     * retain inside the construct mutex closes the race window where
     * `getOrCreateSession` would otherwise return a session and then
     * release the mutex, letting `close()` drop the factory ref to
     * zero before the caller could retain.
     */
    private fun acquireSession(isServer: Boolean, config: TlsConfig): MbedTlsServerSession {
        // Fast path #1: closed-flag check before touching the mutex.
        // Any caller that arrives after close() committed sees the
        // flag here and bails without contending for the lock.
        if (closed.value != 0) throwClosed()

        val key = isServer to config
        // Fast path #2: wait-free cache hit + tryRetain. The
        // snapshot returns the Kotlin session object but its
        // refCount could already have been driven to zero by a
        // concurrent factory close that won the race. tryRetain
        // observes that and we fall through to the mutex path
        // (which will then see closed = 1 and throw cleanly).
        sessions.value[key]?.let {
            if (it.tryRetain()) return it
        }

        pthread_mutex_lock(constructMutex.ptr)
        try {
            // Re-check the closed flag inside the lock — close() may
            // have set it while we were waiting on the mutex.
            if (closed.value != 0) throwClosed()

            // Double-check under the lock — another thread may have
            // constructed and installed our session while we were
            // waiting on the mutex. Inside the mutex, close() is
            // blocked, so a session still in the map is guaranteed
            // alive with at least the factory ref; a plain retain
            // is safe.
            sessions.value[key]?.let {
                it.retain()
                return it
            }

            // Serialised construction. PSA Crypto's key store is not
            // safe under concurrent `mbedtls_x509_crt_parse` /
            // `mbedtls_pk_parse_key` on independent struct pointers
            // when MBEDTLS_THREADING_C is disabled at the C build;
            // holding the mutex across construction prevents two
            // threads (same-key first burst, or distinct-key
            // multi-connector startup) from PSA-racing.
            // New session arrives with refCount=1 (the factory's
            // own cache reference). Retain once more for the codec
            // we're about to construct — caller invariant is
            // documented in [acquireSession]'s contract.
            val newSession = MbedTlsServerSession(isServer, config)
            newSession.retain()
            // Plain set is safe: the construct mutex makes us the
            // sole writer (other constructors are blocked on the
            // mutex; the closed-flag check above blocks close()
            // from draining concurrently).
            sessions.value = sessions.value + (key to newSession)
            return newSession
        } finally {
            pthread_mutex_unlock(constructMutex.ptr)
        }
    }

    override fun close() {
        // Acquire the construct mutex so we are the sole party
        // mutating cache + closed flag — any concurrent
        // getOrCreateSession either already entered and we wait for
        // it, or arrives later and bails on the closed-flag fast
        // path.
        pthread_mutex_lock(constructMutex.ptr)
        try {
            // Idempotent close.
            if (closed.value != 0) return
            closed.value = 1

            val drained = sessions.getAndSet(emptyMap())
            // Drop the factory's reference on each cached session.
            // The session's underlying structs are freed only when
            // the last referent (this drop or the last live codec's
            // close, whichever comes second) releases.
            drained.values.forEach { it.release() }
        } finally {
            pthread_mutex_unlock(constructMutex.ptr)
        }

        // Deliberately *do not* call pthread_mutex_destroy or
        // arena.clear(). POSIX gives us no way to prove "all
        // would-be waiters have observed closed and bailed" before
        // destroying the mutex, and destroying a held / racy mutex
        // is UB. Letting the small per-factory pthread_mutex_t +
        // Arena bookkeeping outlive close() is the simpler, safer
        // trade-off for a typical per-server-lifetime factory; the
        // OS reclaims at process exit. See class KDoc "Concurrency"
        // for the trade-off rationale.
    }

    private fun throwClosed(): Nothing =
        throw IllegalStateException("MbedTlsCodecFactory is closed")
}
