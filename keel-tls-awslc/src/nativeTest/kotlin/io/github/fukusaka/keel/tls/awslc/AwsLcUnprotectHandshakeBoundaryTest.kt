@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.tls.awslc

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.server.TlsCodecServerInstaller
import io.github.fukusaka.keel.server.TlsServerConfig
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsVerifyMode
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.SIGTERM
import platform.posix._exit
import platform.posix.execl
import platform.posix.fork
import platform.posix.kill
import platform.posix.usleep
import platform.posix.waitpid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Regression test for the handshake byte-count conflation — pre-fix the first byte AWS-LC's
 * [AwsLcCodec.unprotect] delivered to the downstream pipeline was a
 * spurious uninitialised `\x00` (a side effect of conflating
 * `SSL_do_handshake`'s `ret == 1` success return with `SSL_read`'s
 * `ret == 1` "one plaintext byte" return — the code then advanced
 * `plaintext.writerIndex += 1` and propagated whatever was sitting
 * in the freshly allocated plaintext buffer at offset 0).
 *
 * `keel-codec-http`'s `HttpRequestDecoder` is tolerant of garbage at
 * the start of the request line (it parses method + path + version by
 * splitting on spaces and any non-matching method falls into a custom
 * `HttpMethod`, while the benchmark routing matches on path only). The
 * Ktor adapter is not — every HTTPS request through `ktor-cio-keel-*`
 * / `ktor-keel-*` × AWS-LC turned into `405 Method Not Allowed` for a
 * path that did exist.
 *
 * **The test**: install a byte-level capture handler downstream of
 * [io.github.fukusaka.keel.tls.TlsHandler] (no HTTP decoder), have a
 * real curl client perform a TLS 1.3 handshake + send an HTTP/1.1
 * request, and assert the very first byte the server sees is `'G'`
 * — pre-fix it was `\x00`.
 */
class AwsLcUnprotectHandshakeBoundaryTest {

    private val tlsConfig = TlsConfig(
        certificates = TlsCertificateSource.Pem(
            TestCertificates.SERVER_CERT,
            TestCertificates.SERVER_KEY,
        ),
        verifyMode = TlsVerifyMode.NONE,
    )

    @Test
    fun `first plaintext byte after handshake is HTTP request - not stray null`() = runBlocking {
        withTimeout(10.seconds) {
            val factory = AwsLcCodecFactory()
            val engine = createTestEngine()
            val firstByte = CompletableDeferred<Int>()
            val collected = StringBuilder()

            val server = engine.bindPipeline(
                "127.0.0.1",
                0,
                config = TlsServerConfig(tlsConfig, TlsCodecServerInstaller(factory)),
            ) { channel ->
                channel.pipeline.addLast("capture", FirstByteCaptureHandler(firstByte, collected))
            }
            val port = (server.localAddress as InetSocketAddress).port

            usleep(SERVER_START_DELAY_US)

            // Fire-and-forget curl — we do not need the response, only the
            // bytes the server received post-handshake.
            val pid = forkCurl(port, "/hello")

            val seen = try {
                firstByte.await()
            } finally {
                kill(pid, SIGTERM)
                waitpid(pid, null, 0)
                server.close()
                factory.close()
                engine.close()
            }

            assertEquals(
                'G'.code,
                seen,
                "first plaintext byte after AWS-LC TLS 1.3 handshake should be 'G' (HTTP request line " +
                    "start), got 0x${seen.toString(16).padStart(2, '0')}. Collected so far: " +
                    "${collected.take(64)}",
            )
            assertTrue(
                collected.startsWith("GET /hello"),
                "expected 'GET /hello...' but server received '${collected.take(32)}...'",
            )
        }
    }

    private class FirstByteCaptureHandler(
        private val firstByte: CompletableDeferred<Int>,
        private val collected: StringBuilder,
    ) : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            if (msg !is IoBuf) {
                ctx.propagateRead(msg)
                return
            }
            try {
                val len = msg.readableBytes
                if (len == 0) return
                for (i in 0 until len) {
                    val b = msg.getByte(msg.readerIndex + i).toInt() and 0xff
                    collected.append(b.toChar())
                }
                if (!firstByte.isCompleted) {
                    firstByte.complete(msg.getByte(msg.readerIndex).toInt() and 0xff)
                }
            } finally {
                msg.release()
            }
        }
    }

    private fun forkCurl(port: Int, path: String): Int = memScoped {
        val pipeFds = allocArray<IntVar>(2)
        check(platform.posix.pipe(pipeFds) == 0) { "pipe() failed" }
        val readFd = pipeFds[0]
        val writeFd = pipeFds[1]

        val pid = fork()
        if (pid == 0) {
            platform.posix.close(readFd)
            platform.posix.dup2(writeFd, platform.posix.STDOUT_FILENO)
            platform.posix.close(writeFd)
            usleep(CURL_START_DELAY_US)
            execl(
                "/usr/bin/curl", "curl",
                "-k", "-s",
                "--max-time", "5",
                "--connect-timeout", "3",
                "https://localhost:$port$path",
                null,
            )
            _exit(1)
        }
        platform.posix.close(writeFd)
        platform.posix.close(readFd)
        pid
    }

    companion object {
        private const val SERVER_START_DELAY_US = 200_000u
        private const val CURL_START_DELAY_US = 100_000u
    }
}
