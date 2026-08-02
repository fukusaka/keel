package io.github.fukusaka.keel.tls.awslc

import awslc.BIO_free
import awslc.BIO_new_mem_buf
import awslc.EVP_PKEY_free
import awslc.OPENSSL_init_ssl
import awslc.PEM_read_bio_PrivateKey
import awslc.PEM_read_bio_X509
import awslc.SSL
import awslc.SSL_CTX_free
import awslc.SSL_CTX_new
import awslc.SSL_CTX_use_PrivateKey
import awslc.SSL_CTX_use_certificate
import awslc.SSL_ERROR_WANT_READ
import awslc.SSL_ERROR_WANT_WRITE
import awslc.SSL_accept
import awslc.SSL_free
import awslc.SSL_get_error
import awslc.SSL_new
import awslc.SSL_read
import awslc.SSL_set_fd
import awslc.SSL_shutdown
import awslc.SSL_write
import awslc.TLS_server_method
import awslc.X509_free
import awslc.keel_awslc_create_server
import awslc.keel_awslc_err_string
import awslc.keel_awslc_get_port
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
 * Minimal TLS echo test using AWS-LC (BoringSSL fork, OpenSSL-compatible API).
 *
 * Validates that AWS-LC's OpenSSL-compatible API works identically to OpenSSL 3.x
 * from cinterop perspective. Same test structure as [OpenSslEchoTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AwsLcEchoTest {

    @Test
    fun `AWS-LC server handshake and echo succeeds`() = runBlocking {
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
                // --- Init ---
                OPENSSL_init_ssl(0u, null)

                // --- Create SSL_CTX ---
                val method = TLS_server_method()
                val ctx = SSL_CTX_new(method)
                check(ctx != null) { "SSL_CTX_new failed: ${keel_awslc_err_string()?.toKString()}" }

                // --- Load certificate ---
                val certBytes = SERVER_CERT.encodeToByteArray()
                val certBio = certBytes.usePinned { pinned ->
                    BIO_new_mem_buf(pinned.addressOf(0), certBytes.size.toLong())
                }
                check(certBio != null) { "BIO_new_mem_buf(cert) failed" }
                val x509 = PEM_read_bio_X509(certBio, null, null, null)
                check(x509 != null) { "PEM_read_bio_X509 failed: ${keel_awslc_err_string()?.toKString()}" }
                SSL_CTX_use_certificate(ctx, x509)
                X509_free(x509)
                BIO_free(certBio)

                // --- Load private key ---
                val keyBytes = SERVER_KEY.encodeToByteArray()
                val keyBio = keyBytes.usePinned { pinned ->
                    BIO_new_mem_buf(pinned.addressOf(0), keyBytes.size.toLong())
                }
                check(keyBio != null) { "BIO_new_mem_buf(key) failed" }
                val pkey = PEM_read_bio_PrivateKey(keyBio, null, null, null)
                check(pkey != null) { "PEM_read_bio_PrivateKey failed: ${keel_awslc_err_string()?.toKString()}" }
                SSL_CTX_use_PrivateKey(ctx, pkey)
                EVP_PKEY_free(pkey)
                BIO_free(keyBio)

                // --- Server socket ---
                val serverFd = keel_awslc_create_server(0)
                check(serverFd >= 0) { "create_server failed: $serverFd" }
                val port = keel_awslc_get_port(serverFd)
                check(port > 0) { "failed to get assigned port" }
                setNonBlocking(serverFd)

                // --- curl client ---
                val pid = platform.posix.fork()
                if (pid == 0) {
                    platform.posix.usleep(300_000u)
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

                // --- Accept (deadline-aware) + handshake ---
                val clientFd = acceptWithDeadline(serverFd, deadline)
                setNonBlocking(clientFd)

                try {
                    val ssl = SSL_new(ctx)
                    check(ssl != null) { "SSL_new failed" }
                    SSL_set_fd(ssl, clientFd)

                    sslOpWithDeadline("SSL_accept", ssl, clientFd, deadline) { SSL_accept(ssl) }

                    // --- Read request, send response ---
                    val buf = ByteArray(4096)
                    val n = buf.usePinned { pinned ->
                        sslOpWithDeadline("SSL_read", ssl, clientFd, deadline) {
                            SSL_read(ssl, pinned.addressOf(0), buf.size)
                        }
                    }
                    println("Server received $n bytes: ${buf.decodeToString(0, n).lines().first()}")

                    val body = "Hello, AWS-LC TLS!"
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
     * Block-equivalent `accept(2)` with a wall-clock deadline. Server fd must
     * already be non-blocking.
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
     * Run an SSL op under a deadline. On `SSL_ERROR_WANT_READ` /
     * `SSL_ERROR_WANT_WRITE` poll the underlying fd within the remaining
     * budget and retry.
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
                error("$label failed: err=$err ${keel_awslc_err_string()?.toKString()}")
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
    }
}
