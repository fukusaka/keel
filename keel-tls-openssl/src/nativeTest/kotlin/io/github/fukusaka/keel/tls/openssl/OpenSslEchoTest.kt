package io.github.fukusaka.keel.tls.openssl

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import openssl.BIO_free
import openssl.BIO_new_mem_buf
import openssl.EVP_PKEY_free
import openssl.OPENSSL_init_ssl
import openssl.PEM_read_bio_PrivateKey
import openssl.PEM_read_bio_X509
import openssl.SSL
import openssl.SSL_CTX
import openssl.SSL_CTX_free
import openssl.SSL_CTX_new
import openssl.SSL_CTX_use_PrivateKey
import openssl.SSL_CTX_use_certificate
import openssl.SSL_ERROR_WANT_READ
import openssl.SSL_ERROR_WANT_WRITE
import openssl.SSL_accept
import openssl.SSL_free
import openssl.SSL_get_error
import openssl.SSL_new
import openssl.SSL_read
import openssl.SSL_set_fd
import openssl.SSL_shutdown
import openssl.SSL_write
import openssl.TLS_server_method
import openssl.X509_free
import openssl.keel_openssl_create_server
import openssl.keel_openssl_err_string
import openssl.keel_openssl_get_port
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.O_NONBLOCK
import platform.posix.POLLIN
import platform.posix.POLLOUT
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.poll
import platform.posix.pollfd
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Minimal TLS echo test using OpenSSL 3.x.
 *
 * Validates:
 * - cinterop binding works (SSL_CTX, SSL, BIO)
 * - Certificate and private key loading from PEM buffers via BIO_mem
 * - TLS handshake success (server-side, blocking)
 * - HTTP response to curl client
 *
 * Uses POSIX socket (via C wrapper) + OpenSSL SSL_read/SSL_write.
 */
@OptIn(ExperimentalForeignApi::class)
class OpenSslEchoTest {

    @Test
    fun `OpenSSL server handshake and echo succeeds`() = runBlocking {
        // Two layers of timeout protection:
        //  1. Outer `withTimeout(30.seconds)` — cooperative cancellation between
        //     suspending boundaries (none here, but rule compliance).
        //  2. Inner `deadline` threaded into `acceptWithDeadline` / `sslOpWithDeadline` —
        //     real wall-clock deadline enforced via non-blocking fd + `poll(2)`.
        //     Required because `accept(2)` and `SSL_read/write/accept` in blocking
        //     mode park inside the kernel and are not interruptible by `withTimeout`.
        withTimeout(30.seconds) {
            val deadline = TimeSource.Monotonic.markNow() + 30.seconds
            memScoped {
                val ctx = newServerContext()

                // --- Create server socket (port 0 = OS assigns ephemeral port) ---
                val serverFd = keel_openssl_create_server(0)
                check(serverFd >= 0) { "create_server failed: $serverFd" }
                val port = keel_openssl_get_port(serverFd)
                check(port > 0) { "failed to get assigned port" }
                setNonBlocking(serverFd)

                val pid = forkCurl(port)

                // --- Accept client (deadline-aware) ---
                val clientFd = acceptWithDeadline(serverFd, deadline)
                setNonBlocking(clientFd)

                try {
                    // --- SSL handshake ---
                    val ssl = SSL_new(ctx)
                    check(ssl != null) { "SSL_new failed" }
                    SSL_set_fd(ssl, clientFd)

                    sslOpWithDeadline("SSL_accept", ssl, clientFd, deadline) { SSL_accept(ssl) }

                    // --- Read HTTP request, send response ---
                    val buf = ByteArray(4096)
                    val n = buf.usePinned { pinned ->
                        sslOpWithDeadline("SSL_read", ssl, clientFd, deadline) {
                            SSL_read(ssl, pinned.addressOf(0), buf.size)
                        }
                    }

                    val received = buf.decodeToString(0, n)
                    println("Server received $n bytes: ${received.lines().first()}")

                    val body = "Hello, OpenSSL TLS!"
                    val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                    val responseBytes = response.encodeToByteArray()
                    responseBytes.usePinned { pinned ->
                        sslOpWithDeadline("SSL_write", ssl, clientFd, deadline) {
                            SSL_write(ssl, pinned.addressOf(0), responseBytes.size)
                        }
                    }

                    // --- Cleanup ---
                    SSL_shutdown(ssl)
                    SSL_free(ssl)
                } finally {
                    platform.posix.close(clientFd)
                    platform.posix.close(serverFd)
                    SSL_CTX_free(ctx)

                    platform.posix.kill(pid, platform.posix.SIGTERM)
                    platform.posix.waitpid(pid, null, 0)
                }
                Unit
            }
        }
    }

    /**
     * Builds a server `SSL_CTX` carrying [SERVER_CERT] and [SERVER_KEY].
     *
     * The caller owns the returned context and must `SSL_CTX_free` it. Each PEM is
     * loaded through a memory BIO, which is freed here along with the parsed X509 and
     * EVP_PKEY — those are copied into the context by the `use_*` calls.
     */
    private fun newServerContext(): CPointer<SSL_CTX> {
        OPENSSL_init_ssl(0u, null)

        val method = TLS_server_method()
        val ctx = SSL_CTX_new(method)
        check(ctx != null) { "SSL_CTX_new failed: ${keel_openssl_err_string()?.toKString()}" }

        val certBytes = SERVER_CERT.encodeToByteArray()
        val certBio = certBytes.usePinned { pinned ->
            BIO_new_mem_buf(pinned.addressOf(0), certBytes.size)
        }
        check(certBio != null) { "BIO_new_mem_buf(cert) failed" }
        val x509 = PEM_read_bio_X509(certBio, null, null, null)
        check(x509 != null) { "PEM_read_bio_X509 failed: ${keel_openssl_err_string()?.toKString()}" }
        val certRet = SSL_CTX_use_certificate(ctx, x509)
        check(certRet == 1) { "SSL_CTX_use_certificate failed: ${keel_openssl_err_string()?.toKString()}" }
        X509_free(x509)
        BIO_free(certBio)

        val keyBytes = SERVER_KEY.encodeToByteArray()
        val keyBio = keyBytes.usePinned { pinned ->
            BIO_new_mem_buf(pinned.addressOf(0), keyBytes.size)
        }
        check(keyBio != null) { "BIO_new_mem_buf(key) failed" }
        val pkey = PEM_read_bio_PrivateKey(keyBio, null, null, null)
        check(pkey != null) { "PEM_read_bio_PrivateKey failed: ${keel_openssl_err_string()?.toKString()}" }
        val keyRet = SSL_CTX_use_PrivateKey(ctx, pkey)
        check(keyRet == 1) { "SSL_CTX_use_PrivateKey failed: ${keel_openssl_err_string()?.toKString()}" }
        EVP_PKEY_free(pkey)
        BIO_free(keyBio)

        return ctx
    }

    /**
     * Forks a curl that fetches `/hello` from [port] over TLS, and returns its pid.
     *
     * The sleep lets the parent reach `accept` first; the child never returns, so the
     * `_exit` is only reached if `execl` itself fails.
     */
    private fun forkCurl(port: Int): Int {
        val pid = platform.posix.fork()
        if (pid == 0) {
            platform.posix.usleep(CURL_START_DELAY_US)
            platform.posix.execl(
                "/usr/bin/curl",
                "curl",
                "-k",
                "-s",
                "https://localhost:$port/hello",
                null,
            )
            platform.posix._exit(1)
        }
        return pid
    }

    /**
     * Switch [fd] to non-blocking mode. Required for `poll(2)`-based deadline
     * enforcement in [acceptWithDeadline] / [sslOpWithDeadline].
     */
    private fun setNonBlocking(fd: Int) {
        val flags = fcntl(fd, F_GETFL, 0)
        check(flags >= 0) { "fcntl(F_GETFL) failed: errno=$errno" }
        val r = fcntl(fd, F_SETFL, flags or O_NONBLOCK)
        check(r >= 0) { "fcntl(F_SETFL O_NONBLOCK) failed: errno=$errno" }
    }

    /**
     * Block-equivalent `accept(2)` with a wall-clock deadline. The server fd
     * must already be non-blocking. Polls for POLLIN within the remaining
     * budget, then calls accept; loops on EAGAIN/EINTR.
     */
    private fun acceptWithDeadline(serverFd: Int, deadline: TimeSource.Monotonic.ValueTimeMark): Int {
        while (true) {
            val remainingMs = remainingMillis(deadline)
            check(remainingMs > 0) { "accept(): deadline exceeded" }
            pollOrThrow(serverFd, POLLIN.toShort(), remainingMs, "accept")
            val client = platform.posix.accept(serverFd, null, null)
            if (client >= 0) return client
            val e = errno
            if (e != EAGAIN && e != EWOULDBLOCK && e != EINTR) {
                error("accept(): errno=$e")
            }
        }
    }

    /**
     * Run an SSL operation under a deadline. On `SSL_ERROR_WANT_READ` /
     * `SSL_ERROR_WANT_WRITE` the caller polls the underlying fd for the
     * requested direction within the remaining budget and retries.
     */
    private inline fun sslOpWithDeadline(
        label: String,
        ssl: CPointer<SSL>?,
        fd: Int,
        deadline: TimeSource.Monotonic.ValueTimeMark,
        op: () -> Int,
    ): Int {
        while (true) {
            val ret = op()
            if (ret > 0) return ret
            val err = SSL_get_error(ssl, ret)
            val wantRead = err == SSL_ERROR_WANT_READ
            val wantWrite = err == SSL_ERROR_WANT_WRITE
            if (!wantRead && !wantWrite) {
                error("$label failed: err=$err ${keel_openssl_err_string()?.toKString()}")
            }
            val remainingMs = remainingMillis(deadline)
            check(remainingMs > 0) { "$label: deadline exceeded" }
            val events = if (wantRead) POLLIN else POLLOUT
            pollOrThrow(fd, events.toShort(), remainingMs, label)
        }
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
