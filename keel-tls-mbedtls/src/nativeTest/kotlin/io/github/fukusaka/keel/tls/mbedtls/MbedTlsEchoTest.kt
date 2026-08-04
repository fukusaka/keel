package io.github.fukusaka.keel.tls.mbedtls

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mbedtls.MBEDTLS_ERR_SSL_WANT_READ
import mbedtls.MBEDTLS_ERR_SSL_WANT_WRITE
import mbedtls.MBEDTLS_NET_PROTO_TCP
import mbedtls.MBEDTLS_SSL_IS_SERVER
import mbedtls.MBEDTLS_SSL_PRESET_DEFAULT
import mbedtls.MBEDTLS_SSL_TRANSPORT_STREAM
import mbedtls.keel_mbedtls_get_port
import mbedtls.keel_mbedtls_net_get_fd
import mbedtls.keel_mbedtls_ssl_set_bio_net
import mbedtls.keel_mbedtls_strerror
import mbedtls.mbedtls_net_accept
import mbedtls.mbedtls_net_bind
import mbedtls.mbedtls_net_context
import mbedtls.mbedtls_net_free
import mbedtls.mbedtls_net_init
import mbedtls.mbedtls_net_set_nonblock
import mbedtls.mbedtls_pk_context
import mbedtls.mbedtls_pk_free
import mbedtls.mbedtls_pk_init
import mbedtls.mbedtls_pk_parse_key
import mbedtls.mbedtls_ssl_conf_ca_chain
import mbedtls.mbedtls_ssl_conf_own_cert
import mbedtls.mbedtls_ssl_config
import mbedtls.mbedtls_ssl_config_defaults
import mbedtls.mbedtls_ssl_config_free
import mbedtls.mbedtls_ssl_config_init
import mbedtls.mbedtls_ssl_context
import mbedtls.mbedtls_ssl_free
import mbedtls.mbedtls_ssl_handshake
import mbedtls.mbedtls_ssl_init
import mbedtls.mbedtls_ssl_read
import mbedtls.mbedtls_ssl_setup
import mbedtls.mbedtls_ssl_write
import mbedtls.mbedtls_x509_crt
import mbedtls.mbedtls_x509_crt_free
import mbedtls.mbedtls_x509_crt_init
import mbedtls.mbedtls_x509_crt_parse
import mbedtls.psa_crypto_init
import platform.posix.EINTR
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.SIGTERM
import platform.posix._exit
import platform.posix.errno
import platform.posix.execl
import platform.posix.fork
import platform.posix.kill
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.usleep
import platform.posix.waitpid
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Minimal TLS echo test using Mbed TLS 4.x net_sockets (blocking I/O).
 *
 * Validates:
 * - cinterop binding works (structs, functions, callbacks)
 * - PSA crypto initialization (4.x: psa_crypto_init replaces entropy/ctr_drbg)
 * - Certificate and private key loading from PEM files
 * - TLS handshake success (server-side)
 * - Encrypted data round-trip
 *
 * Uses mbedtls_net_* for blocking TCP — no keel Channel/Pipeline involvement.
 * A raw TCP client connects and performs TLS handshake + echo.
 */
@OptIn(ExperimentalForeignApi::class)
class MbedTlsEchoTest {

    @Test
    fun `Mbed TLS server handshake and echo succeeds`() = runBlocking {
        // Two layers of timeout protection:
        //  1. Outer `withTimeout(30.seconds)` — cooperative cancellation between
        //     suspending boundaries (none here, but rule compliance).
        //  2. Inner `deadline` threaded into `acceptWithDeadline` / `sslOpWithDeadline` —
        //     real wall-clock deadline enforced via non-blocking fd + `poll(2)`.
        //     Required because `mbedtls_net_accept` and `mbedtls_ssl_*` in blocking
        //     mode park inside the kernel and are not interruptible by `withTimeout`.
        withTimeout(30.seconds) {
            val deadline = TimeSource.Monotonic.markNow() + 30.seconds
            memScoped {
                val psaRet = psa_crypto_init().toInt()
                check(psaRet == 0) { "psa_crypto_init failed: $psaRet" }

                val srvcert = parseServerCert()
                val pkey = parseServerKey()
                val conf = newServerConfig(srvcert, pkey)
                val ssl = newSslContext(conf)
                val listenFd = bindEphemeralListener()
                val port = keel_mbedtls_get_port(listenFd.ptr)
                check(port > 0) { "failed to get assigned port" }

                val pid = forkCurl(port)

                val clientFd = acceptWithDeadline(listenFd, deadline)
                val clientSockFd = keel_mbedtls_net_get_fd(clientFd.ptr)

                try {
                    // --- Set BIO and handshake (deadline-aware) ---
                    // Use C wrapper because mbedtls_net_send/recv can't be passed as
                    // CFunction pointers directly from Kotlin/Native.
                    keel_mbedtls_ssl_set_bio_net(ssl.ptr, clientFd.ptr)

                    sslOpWithDeadline("ssl_handshake", clientSockFd, deadline) {
                        mbedtls_ssl_handshake(ssl.ptr)
                    }

                    // --- Read HTTP request from curl, send HTTP response ---
                    val buf = ByteArray(4096)
                    val n = buf.usePinned { pinned ->
                        sslOpWithDeadline("ssl_read", clientSockFd, deadline) {
                            mbedtls_ssl_read(ssl.ptr, pinned.addressOf(0).reinterpret(), buf.size.convert())
                        }
                    }

                    val received = buf.decodeToString(0, n)
                    println("Server received $n bytes: ${received.lines().first()}")

                    // Send a minimal HTTP response
                    val body = "Hello, TLS!"
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    val responseBytes = response.encodeToByteArray()
                    responseBytes.usePinned { pinned ->
                        sslOpWithDeadline("ssl_write", clientSockFd, deadline) {
                            mbedtls_ssl_write(ssl.ptr, pinned.addressOf(0).reinterpret(), responseBytes.size.convert())
                        }
                    }
                } finally {
                    // --- Cleanup ---
                    mbedtls_ssl_free(ssl.ptr)
                    mbedtls_ssl_config_free(conf.ptr)
                    mbedtls_net_free(clientFd.ptr)
                    mbedtls_net_free(listenFd.ptr)
                    mbedtls_x509_crt_free(srvcert.ptr)
                    mbedtls_pk_free(pkey.ptr)

                    // Kill client process
                    kill(pid, SIGTERM)
                    waitpid(pid, null, 0)
                }
                Unit
            }
        }
    }

    /**
     * Run a Mbed TLS op under a wall-clock deadline. On
     * `MBEDTLS_ERR_SSL_WANT_READ` / `MBEDTLS_ERR_SSL_WANT_WRITE` the caller
     * polls the underlying fd for the requested direction within the remaining
     * budget and retries.
     */
    private inline fun sslOpWithDeadline(
        label: String,
        fd: Int,
        deadline: TimeSource.Monotonic.ValueTimeMark,
        op: () -> Int,
    ): Int {
        while (true) {
            val ret = op()
            if (ret >= 0) return ret
            val wantRead = ret == MBEDTLS_ERR_SSL_WANT_READ
            val wantWrite = ret == MBEDTLS_ERR_SSL_WANT_WRITE
            if (!wantRead && !wantWrite) {
                error("$label failed: ret=$ret ${keel_mbedtls_strerror(ret)?.toKString()}")
            }
            val remainingMs = remainingMillis(deadline)
            check(remainingMs > 0) { "$label: deadline exceeded" }
            val events = if (wantRead) POLLIN else POLLOUT
            pollOrThrow(fd, events.toShort(), remainingMs, label)
        }
    }

    /**
     * Parses [SERVER_CERT] into a freshly initialised chain allocated in this scope.
     *
     * `mbedtls_x509_crt_parse` requires a NUL-terminated PEM and a size that counts
     * the terminator, which is why the byte array gets a trailing zero appended.
     */
    private fun MemScope.parseServerCert(): mbedtls_x509_crt {
        val srvcert = alloc<mbedtls_x509_crt>()
        mbedtls_x509_crt_init(srvcert.ptr)
        val certPem = SERVER_CERT.encodeToByteArray() + 0
        val certRet = certPem.usePinned { pinned ->
            mbedtls_x509_crt_parse(srvcert.ptr, pinned.addressOf(0).reinterpret(), certPem.size.convert())
        }
        check(certRet == 0) { "cert parse failed: ${keel_mbedtls_strerror(certRet)?.toKString()}" }
        return srvcert
    }

    /** Parses [SERVER_KEY] into a freshly initialised key context allocated in this scope. */
    private fun MemScope.parseServerKey(): mbedtls_pk_context {
        val pkey = alloc<mbedtls_pk_context>()
        mbedtls_pk_init(pkey.ptr)
        val keyPem = SERVER_KEY.encodeToByteArray() + 0
        val keyRet = keyPem.usePinned { pinned ->
            mbedtls_pk_parse_key(pkey.ptr, pinned.addressOf(0).reinterpret(), keyPem.size.convert(), null, 0u)
        }
        check(keyRet == 0) { "key parse failed: ${keel_mbedtls_strerror(keyRet)?.toKString()}" }
        return pkey
    }

    /**
     * Server-side TLS config presenting [srvcert] / [pkey].
     *
     * No `mbedtls_ssl_conf_rng` call: 4.x configures the RNG through PSA.
     */
    private fun MemScope.newServerConfig(
        srvcert: mbedtls_x509_crt,
        pkey: mbedtls_pk_context,
    ): mbedtls_ssl_config {
        val conf = alloc<mbedtls_ssl_config>()
        mbedtls_ssl_config_init(conf.ptr)
        val defaultsRet = mbedtls_ssl_config_defaults(
            conf.ptr,
            MBEDTLS_SSL_IS_SERVER,
            MBEDTLS_SSL_TRANSPORT_STREAM,
            MBEDTLS_SSL_PRESET_DEFAULT,
        )
        check(defaultsRet == 0) { "ssl_config_defaults failed: ${keel_mbedtls_strerror(defaultsRet)?.toKString()}" }

        mbedtls_ssl_conf_ca_chain(conf.ptr, srvcert.ptr, null)
        val ownCertRet = mbedtls_ssl_conf_own_cert(conf.ptr, srvcert.ptr, pkey.ptr)
        check(ownCertRet == 0) { "ssl_conf_own_cert failed: ${keel_mbedtls_strerror(ownCertRet)?.toKString()}" }
        return conf
    }

    /** SSL context set up against [conf], allocated in this scope. */
    private fun MemScope.newSslContext(conf: mbedtls_ssl_config): mbedtls_ssl_context {
        val ssl = alloc<mbedtls_ssl_context>()
        mbedtls_ssl_init(ssl.ptr)
        val setupRet = mbedtls_ssl_setup(ssl.ptr, conf.ptr)
        check(setupRet == 0) { "ssl_setup failed: ${keel_mbedtls_strerror(setupRet)?.toKString()}" }
        return ssl
    }

    /**
     * Listener bound to an OS-assigned port, allocated in this scope.
     *
     * Non-blocking, so `mbedtls_net_accept` returns `MBEDTLS_ERR_SSL_WANT_READ`
     * promptly and [acceptWithDeadline]'s `poll(2)` loop can enforce a wall clock.
     */
    private fun MemScope.bindEphemeralListener(): mbedtls_net_context {
        val listenFd = alloc<mbedtls_net_context>()
        mbedtls_net_init(listenFd.ptr)
        val bindRet = mbedtls_net_bind(listenFd.ptr, null, "0", MBEDTLS_NET_PROTO_TCP)
        check(bindRet == 0) { "net_bind failed: ${keel_mbedtls_strerror(bindRet)?.toKString()}" }
        val nonblockRet = mbedtls_net_set_nonblock(listenFd.ptr)
        check(nonblockRet == 0) {
            "net_set_nonblock(listen) failed: ${keel_mbedtls_strerror(nonblockRet)?.toKString()}"
        }
        return listenFd
    }

    /**
     * Forks a curl that fetches `/hello` from [port] over TLS, and returns its pid.
     *
     * The sleep lets the parent reach `accept` first; the child never returns, so the
     * `_exit` is only reached if `execl` itself fails.
     */
    private fun forkCurl(port: Int): Int {
        val pid = fork()
        if (pid == 0) {
            usleep(CURL_START_DELAY_US)
            execl(
                "/usr/bin/curl",
                "curl",
                "-k",
                "-s",
                "https://localhost:$port/hello",
                null,
            )
            _exit(1)
        }
        return pid
    }

    /**
     * Accepts one connection on [listenFd] before [deadline], returning a non-blocking
     * client context allocated in this scope.
     *
     * The listener is non-blocking, so `accept` reports `WANT_READ` instead of parking
     * in the kernel; each of those waits on `poll(2)` for whatever is left of the
     * deadline, which is what makes the wall clock enforceable at all.
     */
    private fun MemScope.acceptWithDeadline(
        listenFd: mbedtls_net_context,
        deadline: TimeSource.Monotonic.ValueTimeMark,
    ): mbedtls_net_context {
        val clientFd = alloc<mbedtls_net_context>()
        mbedtls_net_init(clientFd.ptr)
        val listenSockFd = keel_mbedtls_net_get_fd(listenFd.ptr)
        while (true) {
            val ret = mbedtls_net_accept(listenFd.ptr, clientFd.ptr, null, 0u, null)
            if (ret == 0) break
            check(ret == MBEDTLS_ERR_SSL_WANT_READ) {
                "net_accept failed: ${keel_mbedtls_strerror(ret)?.toKString()}"
            }
            val remainingMs = remainingMillis(deadline)
            check(remainingMs > 0) { "net_accept: deadline exceeded" }
            pollOrThrow(listenSockFd, POLLIN.toShort(), remainingMs, "net_accept")
        }
        val nonblockRet = mbedtls_net_set_nonblock(clientFd.ptr)
        check(nonblockRet == 0) {
            "net_set_nonblock(client) failed: ${keel_mbedtls_strerror(nonblockRet)?.toKString()}"
        }
        return clientFd
    }

    private fun pollOrThrow(fd: Int, events: Short, timeoutMs: Int, label: String) {
        memScoped {
            val pfd = alloc<pollfd>().apply {
                this.fd = fd
                this.events = events
                this.revents = 0
            }
            val r = poll(pfd.ptr, 1u, timeoutMs)
            when {
                r < 0 && errno == EINTR -> Unit
                r < 0 -> error("$label poll(): errno=$errno")
                r == 0 -> error("$label: deadline exceeded waiting for fd ready")
            }
        }
    }

    private fun remainingMillis(deadline: TimeSource.Monotonic.ValueTimeMark): Int {
        val remaining = deadline - TimeSource.Monotonic.markNow()
        if (remaining.isNegative()) return 0
        return remaining.inWholeMilliseconds.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    companion object {
        private val SERVER_CERT = TestCertificates.SERVER_CERT
        private val SERVER_KEY = TestCertificates.SERVER_KEY

        /** Head start for the parent to reach `accept` before curl connects. */
        private const val CURL_START_DELAY_US = 300_000u
    }
}
