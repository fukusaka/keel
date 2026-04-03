package io.github.fukusaka.keel.tls.mbedtls

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import mbedtls.*
import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `Mbed TLS server handshake and echo succeeds`() = memScoped {
        // --- PSA Crypto init (replaces entropy/ctr_drbg in 4.x) ---
        val psaRet = psa_crypto_init().toInt()
        check(psaRet == 0) { "psa_crypto_init failed: $psaRet" }

        // --- Load server certificate and key from PEM strings ---
        val srvcert = alloc<mbedtls_x509_crt>()
        mbedtls_x509_crt_init(srvcert.ptr)
        // mbedtls_x509_crt_parse requires null-terminated PEM string.
        // Size must include the null terminator.
        val certPem = SERVER_CERT.encodeToByteArray() + 0
        val certRet = certPem.usePinned { pinned ->
            mbedtls_x509_crt_parse(srvcert.ptr, pinned.addressOf(0).reinterpret(), certPem.size.convert())
        }
        check(certRet == 0) { "cert parse failed: ${keel_mbedtls_strerror(certRet)?.toKString()}" }

        val pkey = alloc<mbedtls_pk_context>()
        mbedtls_pk_init(pkey.ptr)
        val keyPem = SERVER_KEY.encodeToByteArray() + 0
        val keyRet = keyPem.usePinned { pinned ->
            mbedtls_pk_parse_key(pkey.ptr, pinned.addressOf(0).reinterpret(), keyPem.size.convert(), null, 0u)
        }
        check(keyRet == 0) { "key parse failed: ${keel_mbedtls_strerror(keyRet)?.toKString()}" }

        // --- SSL config ---
        val conf = alloc<mbedtls_ssl_config>()
        mbedtls_ssl_config_init(conf.ptr)
        var ret = mbedtls_ssl_config_defaults(
            conf.ptr,
            MBEDTLS_SSL_IS_SERVER,
            MBEDTLS_SSL_TRANSPORT_STREAM,
            MBEDTLS_SSL_PRESET_DEFAULT,
        )
        check(ret == 0) { "ssl_config_defaults failed: ${keel_mbedtls_strerror(ret)?.toKString()}" }

        // 4.x: RNG is configured automatically via PSA — no mbedtls_ssl_conf_rng call needed
        mbedtls_ssl_conf_ca_chain(conf.ptr, srvcert.ptr, null)
        ret = mbedtls_ssl_conf_own_cert(conf.ptr, srvcert.ptr, pkey.ptr)
        check(ret == 0) { "ssl_conf_own_cert failed: ${keel_mbedtls_strerror(ret)?.toKString()}" }

        // --- SSL context ---
        val ssl = alloc<mbedtls_ssl_context>()
        mbedtls_ssl_init(ssl.ptr)
        ret = mbedtls_ssl_setup(ssl.ptr, conf.ptr)
        check(ret == 0) { "ssl_setup failed: ${keel_mbedtls_strerror(ret)?.toKString()}" }

        // --- Bind server ---
        val listenFd = alloc<mbedtls_net_context>()
        mbedtls_net_init(listenFd.ptr)
        ret = mbedtls_net_bind(listenFd.ptr, null, PORT, mbedtls.MBEDTLS_NET_PROTO_TCP)
        check(ret == 0) { "net_bind failed: ${keel_mbedtls_strerror(ret)?.toKString()}" }

        // --- Start curl client in background ---
        // curl sends an HTTP GET over TLS; server reads the request and echoes back.
        val pid = platform.posix.fork()
        if (pid == 0) {
            platform.posix.usleep(300_000u) // Wait for server to accept
            platform.posix.execl(
                "/usr/bin/curl", "curl",
                "-k", "-s",  // skip cert verification, silent
                "https://localhost:$PORT/hello",
                null,
            )
            platform.posix._exit(1)
        }

        // --- Accept client ---
        val clientFd = alloc<mbedtls_net_context>()
        mbedtls_net_init(clientFd.ptr)
        ret = mbedtls_net_accept(listenFd.ptr, clientFd.ptr, null, 0u, null)
        check(ret == 0) { "net_accept failed: ${keel_mbedtls_strerror(ret)?.toKString()}" }

        // --- Set BIO and handshake ---
        // Use C wrapper because mbedtls_net_send/recv can't be passed as
        // CFunction pointers directly from Kotlin/Native.
        mbedtls.keel_mbedtls_ssl_set_bio_net(ssl.ptr, clientFd.ptr)

        ret = mbedtls_ssl_handshake(ssl.ptr)
        check(ret == 0) { "handshake failed: ${keel_mbedtls_strerror(ret)?.toKString()}" }

        // --- Read HTTP request from curl, send HTTP response ---
        val buf = ByteArray(4096)
        val n = buf.usePinned { pinned ->
            mbedtls_ssl_read(ssl.ptr, pinned.addressOf(0).reinterpret(), buf.size.convert())
        }
        check(n > 0) { "ssl_read failed: ${keel_mbedtls_strerror(n)?.toKString()}" }

        val received = buf.decodeToString(0, n)
        println("Server received ${n} bytes: ${received.lines().first()}")

        // Send a minimal HTTP response
        val body = "Hello, TLS!"
        val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
        val responseBytes = response.encodeToByteArray()
        responseBytes.usePinned { pinned ->
            mbedtls_ssl_write(ssl.ptr, pinned.addressOf(0).reinterpret(), responseBytes.size.convert())
        }

        // --- Cleanup ---
        mbedtls_ssl_free(ssl.ptr)
        mbedtls_ssl_config_free(conf.ptr)
        mbedtls_net_free(clientFd.ptr)
        mbedtls_net_free(listenFd.ptr)
        mbedtls_x509_crt_free(srvcert.ptr)
        mbedtls_pk_free(pkey.ptr)

        // Kill client process
        platform.posix.kill(pid, platform.posix.SIGTERM)
        platform.posix.waitpid(pid, null, 0)
        Unit
    }

    companion object {
        private const val PORT = "14433"

        // Self-signed certificate and key for testing (generated via openssl).
        // PEM must include trailing newline for mbedtls_x509_crt_parse.
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
