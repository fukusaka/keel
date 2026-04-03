package io.github.fukusaka.keel.tls.awslc

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import awslc.*
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
        val serverFd = keel_awslc_create_server(PORT)
        check(serverFd >= 0) { "create_server failed: $serverFd" }

        // --- curl client ---
        val pid = platform.posix.fork()
        if (pid == 0) {
            platform.posix.usleep(300_000u)
            platform.posix.execl(
                "/usr/bin/curl", "curl", "-k", "-s",
                "https://localhost:$PORT/hello", null,
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
        private const val PORT = 14435

        private val SERVER_CERT = """
-----BEGIN CERTIFICATE-----
MIIDCTCCAfGgAwIBAgIUaVO1WKzG9gPzYk5Td3h5tNjDl0QwDQYJKoZIhvcNAQEL
BQAwFDESMBAGA1UEAwwJbG9jYWxob3N0MB4XDTI2MDQwMzA0MjcxNloXDTI3MDQw
MzA0MjcxNlowFDESMBAGA1UEAwwJbG9jYWxob3N0MIIBIjANBgkqhkiG9w0BAQEF
AAOCAQ8AMIIBCgKCAQEAshZok7kN0FOmy+QXXPDq4ZI0Dj/f20KYjxku2HdEcMXQ
boyY+Yh4F0Ag3YdQCa9SNwSERXKaxzQCR2FDvxR1tkx7/UFewijuvQmSLt9oqD9M
oI6+mZlwK9StE4MbuLigLoI6MGhRCzAC56ZzhH49cbS1ax4waQGaVh7/ijSz/apo
KCmoHKn1X7AuZJepnjDGwsPI0TX2m6SFAtNanH9M4Wp3uzgvlCFd7FGbwMBj+JuU
YA5cvAy/RgUPTSKjzmSAl6MN9/Uoda4qzJl0fCaZGhGxsVb9txVRCu7YTIz7MIcB
BwyphJtA0CSGa8oTJMGtUqlawGFwyOIIGJjx+CneCQIDAQABo1MwUTAdBgNVHQ4E
FgQU3Kkr9odzVo91JZso0zBsTicdW0cwHwYDVR0jBBgwFoAU3Kkr9odzVo91JZso
0zBsTicdW0cwDwYDVR0TAQH/BAUwAwEB/zANBgkqhkiG9w0BAQsFAAOCAQEAF01R
PJIlhyBh1DgS7JUbQkrhHYHvA/c25OMIQSJ8ClNJHL6yV6lrm8VIxAmPAFoNk7DX
clB3+xiZcUV0Ia1tuOgOnsouJaHQjAWdNcfweHu0mLnxRrBF/OKRDRfasN/XGrEY
xA2XszM9gkm2JrLeSt7GSfhzSykUFXDlGTiA4hExB/gCQN5Hhfkw4HXtiwsrqJTI
dA0v0c6TRwAZKuG5BIzAh9r94fM0NzYvaYamE+/WIm6orpjzUELVKjVebvmAWkN0
DckJ9HFnEw1KPYC/9e7a1JUrkfMgCFcgIdRGQA/qMHISUzQND9Zs/ZnPvhaf+x7N
wIy8X6kST+S43rMGiQ==
-----END CERTIFICATE-----
""".trimIndent() + "\n"

        private val SERVER_KEY = """
-----BEGIN PRIVATE KEY-----
MIIEuwIBADANBgkqhkiG9w0BAQEFAASCBKUwggShAgEAAoIBAQCyFmiTuQ3QU6bL
5Bdc8OrhkjQOP9/bQpiPGS7Yd0RwxdBujJj5iHgXQCDdh1AJr1I3BIRFcprHNAJH
YUO/FHW2THv9QV7CKO69CZIu32ioP0ygjr6ZmXAr1K0Tgxu4uKAugjowaFELMALn
pnOEfj1xtLVrHjBpAZpWHv+KNLP9qmgoKagcqfVfsC5kl6meMMbCw8jRNfabpIUC
01qcf0zhane7OC+UIV3sUZvAwGP4m5RgDly8DL9GBQ9NIqPOZICXow339Sh1rirM
mXR8JpkaEbGxVv23FVEK7thMjPswhwEHDKmEm0DQJIZryhMkwa1SqVrAYXDI4ggY
mPH4Kd4JAgMBAAECggEAB6IQP2yqG+jJ+GlBWxl0Z9C1bHruZF55XYDN0jdidpbz
9RkPoXpo804rWnNnSdL66iLGbJeZ7Lnc8yRHHBSLaxHiKpu3rQjGGtIjMuEegj+c
UDFuF/VMqoRGGtT+xi8bpoKsbdC60IjxRu6Kev5SMeJ1+C5mEsofzFstxsW1hUTL
NvPt+RbuosMGk/uDKFMXYFxKmly6Tr2EMxMTMmtIdb2jCCDpVnXPCUyW2pv6PHu2
tbqQF/UExA1Bz6t6mIxIZieNckWbOcdH+UJyTss0//kRjUMrOg3Xu6pMtDbh679f
2Xoc+mhNkMIvcYS2AJ2713Ok5RmfLNOnj/PAhjYBJQKBgQDh2awW98zzb/FTZ3bl
lH2x/bdsiNzKGZvwxMUa3Id53f1rwHBFvw05cPsaiaaegfkhRFMJUAacTeMIUm7c
K4AZ8iJ0CxD70nzCmOoihZB7keZapNjYIGYLhlQGB5BczKfiL+rIgn5X03kvdL0G
K7uQ8tqwJZzqMWEUCIPNN8S0ewKBgQDJ3Hd3tyUHnWPHqtMDqllW+/E0lDvzDMIL
qti6SENjAWmDY4A9AVt02oSDqGXK47p96cO5/klULRSkjzoW6/54eB8ICIAnObPv
lIYTPXFoEICBCDweu63shfgE/DUE85DH0cI8dgMHsa/4Hq5QM3dCc7jDJLMvVYl8
ErJmdrWaSwJ/bkbawFw+tp7yNsdORss6lK5N4bDyHbxjaCysEXGctOSv2O0d5PBk
hKel9E9CDCNqgdPat7FbiPZ+5JFbkCWtZv3T1NWSdWNRh7Min7iX075pu9jCCMXJ
DdeJL2iCFM3ZK5g6C62sAzY+9e0KXvj7nMr3/Qpgk/mIbT+7G3kfkwKBgDObdMOb
hBENUPw0FRyjyZFuef06RJVf1qBK/nupi+jc7I/VuWxfU3VthGFwhQ246O3V/N8p
PrARkmx73ZsMnJNKCozwN2tP2kvPCfQTSlITnfbfFxe4Xb/RhFYp8JgieQpM+z6f
4ShvahCiL2h7r+rCUSM618CrOqoI0alWghk/AoGBALoo1MDASnYoh9b18siAYuA3
yGIdCqVeuv9SC0duPplXUVQwuYkLDZaIASA8goes6f5UiFEkE8TXYAKTitNUQqob
s0/JN9iAF2/A2ct6J46JuRo8bxt+LdZY2znb8weICRpxx7/Sf+lswHA7OiUJT8UG
XDEgg9dRd2akza/XK5Hj
-----END PRIVATE KEY-----
""".trimIndent() + "\n"
    }
}
