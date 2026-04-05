package io.github.fukusaka.keel.tls.openssl

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import openssl.BIO_free
import openssl.BIO_new_mem_buf
import openssl.EVP_PKEY_free
import openssl.OPENSSL_init_ssl
import openssl.PEM_read_bio_PrivateKey
import openssl.PEM_read_bio_X509
import openssl.SSL_CTX_free
import openssl.SSL_CTX_new
import openssl.SSL_CTX_use_PrivateKey
import openssl.SSL_CTX_use_certificate
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
import kotlin.test.Test

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
    fun `OpenSSL server handshake and echo succeeds`() = memScoped {
        // --- Init OpenSSL ---
        OPENSSL_init_ssl(0u, null)

        // --- Create SSL_CTX ---
        val method = TLS_server_method()
        val ctx = SSL_CTX_new(method)
        check(ctx != null) { "SSL_CTX_new failed: ${keel_openssl_err_string()?.toKString()}" }

        // --- Load certificate from PEM buffer via BIO ---
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

        // --- Load private key from PEM buffer via BIO ---
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

        // --- Create server socket (port 0 = OS assigns ephemeral port) ---
        val serverFd = keel_openssl_create_server(0)
        check(serverFd >= 0) { "create_server failed: $serverFd" }
        val port = keel_openssl_get_port(serverFd)
        check(port > 0) { "failed to get assigned port" }

        // --- Start curl client in background ---
        val pid = platform.posix.fork()
        if (pid == 0) {
            platform.posix.usleep(300_000u)
            platform.posix.execl(
                "/usr/bin/curl", "curl",
                "-k", "-s",
                "https://localhost:$port/hello",
                null,
            )
            platform.posix._exit(1)
        }

        // --- Accept client ---
        val clientFd = platform.posix.accept(serverFd, null, null)
        check(clientFd >= 0) { "accept failed" }

        // --- SSL handshake ---
        val ssl = SSL_new(ctx)
        check(ssl != null) { "SSL_new failed" }
        SSL_set_fd(ssl, clientFd)

        val hsRet = SSL_accept(ssl)
        check(hsRet == 1) {
            val err = SSL_get_error(ssl, hsRet)
            "SSL_accept failed: err=$err ${keel_openssl_err_string()?.toKString()}"
        }

        // --- Read HTTP request, send response ---
        val buf = ByteArray(4096)
        val n = buf.usePinned { pinned ->
            SSL_read(ssl, pinned.addressOf(0), buf.size)
        }
        check(n > 0) { "SSL_read failed: ${SSL_get_error(ssl, n)}" }

        val received = buf.decodeToString(0, n)
        println("Server received ${n} bytes: ${received.lines().first()}")

        val body = "Hello, OpenSSL TLS!"
        val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        val responseBytes = response.encodeToByteArray()
        responseBytes.usePinned { pinned ->
            SSL_write(ssl, pinned.addressOf(0), responseBytes.size)
        }

        // --- Cleanup ---
        SSL_shutdown(ssl)
        SSL_free(ssl)
        platform.posix.close(clientFd)
        platform.posix.close(serverFd)
        SSL_CTX_free(ctx)

        platform.posix.kill(pid, platform.posix.SIGTERM)
        platform.posix.waitpid(pid, null, 0)
        Unit
    }

    companion object {
        private val SERVER_CERT = TestCertificates.SERVER_CERT
        private val SERVER_KEY = TestCertificates.SERVER_KEY
    }
}
