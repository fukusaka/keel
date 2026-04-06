package io.github.fukusaka.keel.tls.awslc

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import awslc.BIO_free
import awslc.BIO_new_mem_buf
import awslc.EVP_PKEY_free
import awslc.OPENSSL_init_ssl
import awslc.PEM_read_bio_PrivateKey
import awslc.PEM_read_bio_X509
import awslc.SSL_CTX_free
import awslc.SSL_CTX_new
import awslc.SSL_CTX_use_PrivateKey
import awslc.SSL_CTX_use_certificate
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
import kotlin.test.Test

/**
 * Minimal TLS echo test using AWS-LC (BoringSSL fork, OpenSSL-compatible API).
 *
 * Validates that AWS-LC's OpenSSL-compatible API works identically to OpenSSL 3.x
 * from cinterop perspective. Same test structure as [OpenSslEchoTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AwsLcEchoTest {

    @Test
    fun `AWS-LC server handshake and echo succeeds`() = memScoped {
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

        // --- curl client ---
        val pid = platform.posix.fork()
        if (pid == 0) {
            platform.posix.usleep(300_000u)
            platform.posix.execl(
                "/usr/bin/curl", "curl", "-k", "-s",
                "https://localhost:$port/hello", null,
            )
            platform.posix._exit(1)
        }

        // --- Accept + handshake ---
        val clientFd = platform.posix.accept(serverFd, null, null)
        check(clientFd >= 0) { "accept failed" }

        val ssl = SSL_new(ctx)
        check(ssl != null) { "SSL_new failed" }
        SSL_set_fd(ssl, clientFd)

        val hsRet = SSL_accept(ssl)
        check(hsRet == 1) {
            "SSL_accept failed: err=${SSL_get_error(ssl, hsRet)} ${keel_awslc_err_string()?.toKString()}"
        }

        // --- Read request, send response ---
        val buf = ByteArray(4096)
        val n = buf.usePinned { pinned ->
            SSL_read(ssl, pinned.addressOf(0), buf.size)
        }
        check(n > 0) { "SSL_read failed: ${SSL_get_error(ssl, n)}" }
        println("Server received ${n} bytes: ${buf.decodeToString(0, n).lines().first()}")

        val body = "Hello, AWS-LC TLS!"
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
