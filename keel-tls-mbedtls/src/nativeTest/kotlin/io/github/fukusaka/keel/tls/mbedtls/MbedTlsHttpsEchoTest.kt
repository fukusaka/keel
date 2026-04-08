@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.RoutingHandler
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsConnectorConfig
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
 * Integration test: Native Pipeline + TlsHandler + HTTP codec.
 *
 * Starts an HTTPS server using:
 * - Platform-specific engine (kqueue on macOS, epoll on Linux) via [createTestEngine]
 * - TlsHandler with MbedTlsCodec for TLS record protection
 * - HttpRequestDecoder/HttpResponseEncoder/RoutingHandler for HTTP
 *
 * Validates end-to-end: curl -k -> HTTPS -> TLS decrypt -> HTTP decode ->
 * route -> HTTP encode -> TLS encrypt -> response to curl.
 */
class MbedTlsHttpsEchoTest {

    private val tlsConfig = TlsConfig(
        certificates = TlsCertificateSource.Pem(
            TestCertificates.SERVER_CERT, TestCertificates.SERVER_KEY,
        ),
        verifyMode = TlsVerifyMode.NONE,
    )

    @Test
    fun `HTTPS echo via curl`() {
        val factory = MbedTlsCodecFactory()
        val engine = createTestEngine()

        val response = HttpResponse.ok("Hello, HTTPS!", contentType = "text/plain")
        response.headers.size // warm flatEntries cache

        val server = engine.bindPipeline("127.0.0.1", 0, config = TlsConnectorConfig(tlsConfig, factory)) { channel ->
            channel.pipeline.addLast("encoder", HttpResponseEncoder())
            channel.pipeline.addLast("decoder", HttpRequestDecoder())
            channel.pipeline.addLast("routing", RoutingHandler(mapOf("/hello" to { response })))
        }
        val port = server.localAddress.port

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
    }
}
