@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.RoutingHandler
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.kqueue.KqueueEngine
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsHandler
import io.github.fukusaka.keel.tls.TlsVerifyMode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import mbedtls.MBEDTLS_NET_PROTO_TCP
import mbedtls.keel_mbedtls_get_port
import mbedtls.mbedtls_net_bind
import mbedtls.mbedtls_net_context
import mbedtls.mbedtls_net_free
import mbedtls.mbedtls_net_init
import platform.posix.SIGTERM
import platform.posix.STDOUT_FILENO
import platform.posix._exit
import platform.posix.close
import platform.posix.dup2
import platform.posix.execl
import platform.posix.fork
import platform.posix.kill
import platform.posix.pipe
import platform.posix.read
import platform.posix.usleep
import platform.posix.waitpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test: kqueue Pipeline + TlsHandler + HTTP codec.
 *
 * Starts an HTTPS server using:
 * - KqueueEngine.bindPipeline for transport
 * - TlsHandler with MbedTlsCodec for TLS record protection
 * - HttpRequestDecoder/HttpResponseEncoder/RoutingHandler for HTTP
 *
 * Validates end-to-end: curl -k -> HTTPS -> TLS decrypt -> HTTP decode ->
 * route -> HTTP encode -> TLS encrypt -> response to curl.
 */
class MbedTlsHttpsEchoTest {

    private val tlsConfig = TlsConfig(
        certificates = TlsCertificateSource.Pem(SERVER_CERT, SERVER_KEY),
        verifyMode = TlsVerifyMode.NONE,
    )

    @Test
    fun `kqueue HTTPS echo via curl`() {
        val factory = MbedTlsCodecFactory()
        val engine = KqueueEngine(IoEngineConfig(threads = 1))

        val response = HttpResponse.ok("Hello, HTTPS!", contentType = "text/plain")
        response.headers.size // warm flatEntries cache

        val port = findFreePort()

        val server = engine.bindPipeline("127.0.0.1", port) { pipeline ->
            val codec = factory.createServerCodec(tlsConfig)
            pipeline.addLast("tls", TlsHandler(codec))
            pipeline.addLast("encoder", HttpResponseEncoder())
            pipeline.addLast("decoder", HttpRequestDecoder())
            pipeline.addLast("routing", RoutingHandler(mapOf("/hello" to { response })))
        }

        usleep(200_000u) // 200ms — allow server to start

        val (exitCode, output) = curlHttps(port, "/hello")

        // Cleanup
        server.close()
        factory.close()
        engine.close()

        // Verify curl succeeded and response is correct.
        assertEquals(0, exitCode, "curl exit code")
        // curl output: body + "\n" + http_code (from -w "\n%{http_code}")
        val lines = output.trimEnd().lines()
        assertTrue(lines.size >= 2, "expected body + status code, got: $output")
        assertEquals("Hello, HTTPS!", lines.dropLast(1).joinToString("\n"))
        assertEquals("200", lines.last())
    }

    // --- Test helpers ---

    /**
     * Finds an available ephemeral port using mbedtls_net_bind with port "0".
     *
     * Binds to port 0 (OS assigns ephemeral port), retrieves the assigned port
     * via [keel_mbedtls_get_port], then immediately frees the socket.
     * Small race window between free and bindPipeline, acceptable for tests.
     */
    private fun findFreePort(): Int = memScoped {
        val ctx = alloc<mbedtls_net_context>()
        mbedtls_net_init(ctx.ptr)
        val ret = mbedtls_net_bind(ctx.ptr, null, "0", MBEDTLS_NET_PROTO_TCP)
        check(ret == 0) { "mbedtls_net_bind failed: $ret" }
        val port = keel_mbedtls_get_port(ctx.ptr)
        check(port > 0) { "failed to get assigned port" }
        mbedtls_net_free(ctx.ptr)
        port
    }

    /**
     * Forks curl to make an HTTPS request and captures its output.
     *
     * Uses pipe + dup2 to redirect curl's stdout to the parent process.
     * curl is invoked with `--max-time` and `--connect-timeout` to prevent
     * hangs, and `-w "\n%{http_code}"` to append the HTTP status code.
     *
     * @return Pair of (exit code, stdout output string).
     */
    private fun curlHttps(port: Int, path: String): Pair<Int, String> = memScoped {
        val pipeFds = allocArray<IntVar>(2)
        check(pipe(pipeFds) == 0) { "pipe() failed" }
        val readFd = pipeFds[0]
        val writeFd = pipeFds[1]

        val pid = fork()
        if (pid == 0) {
            // Child: redirect stdout to pipe, then exec curl.
            close(readFd)
            dup2(writeFd, STDOUT_FILENO)
            close(writeFd)
            usleep(100_000u) // 100ms — wait for server to be ready
            execl(
                "/usr/bin/curl", "curl",
                "-k", "-s",
                "--max-time", "5",
                "--connect-timeout", "3",
                "-w", "\n%{http_code}",
                "https://localhost:$port$path",
                null,
            )
            _exit(1) // exec failed
        }

        // Parent: read curl output from pipe.
        close(writeFd)
        val output = readAllFromFd(readFd)
        close(readFd)

        val status = alloc<IntVar>()
        waitpid(pid, status.ptr, 0)
        // WIFEXITED/WEXITSTATUS are C macros — inline the expansion.
        val exited = (status.value and 0x7f) == 0
        val exitCode = if (exited) (status.value shr 8) and 0xff else -1

        // Kill defensively in case curl is still running.
        kill(pid, SIGTERM)

        Pair(exitCode, output)
    }

    private fun readAllFromFd(fd: Int): String {
        val buf = ByteArray(READ_BUF_SIZE)
        val sb = StringBuilder()
        while (true) {
            val n = buf.usePinned { pinned ->
                read(fd, pinned.addressOf(0), buf.size.convert())
            }
            if (n <= 0) break
            sb.append(buf.decodeToString(0, n.toInt()))
        }
        return sb.toString()
    }

    companion object {
        private const val READ_BUF_SIZE = 4096

        // Self-signed test certificate (CN=localhost, RSA 2048-bit).
        // Same certificate as MbedTlsEchoTest — shared for consistency.
        internal val SERVER_CERT = """
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

        internal val SERVER_KEY = """
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
