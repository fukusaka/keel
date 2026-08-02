@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.tls.openssl

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
 * Handshake byte-count contract pin for the OpenSSL backend.
 *
 * The pre-fix `SSL_do_handshake` / `SSL_read` return-value conflation
 * in [OpenSslCodec.unprotect] (see PR #620) was **latent** on this
 * backend: OpenSSL transitions `SSL_is_init_finished` to true right
 * after the server sends its own Finished (TLS 1.3 half-RTT
 * semantics), so the next `unprotect` call is already on the
 * `SSL_read` branch and the conflation never fires in practice.
 * BoringSSL / AWS-LC uses strict semantics (handshake done only after
 * verifying client Finished), keeping the buggy path live for every
 * connection — that is how the byte-count conflation surfaced.
 *
 * This test is therefore Green-only — pre-fix and post-fix both
 * pass on OpenSSL — but it pins the post-fix contract so any future
 * change that moves OpenSSL into BoringSSL-like timing (e.g.
 * disabling half-RTT) would be caught immediately rather than only
 * via end-to-end Ktor 405 failures.
 */
class OpenSslUnprotectHandshakeBoundaryTest {

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
            val factory = OpenSslCodecFactory()
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
                "first plaintext byte after OpenSSL TLS 1.3 handshake should be 'G' (HTTP request line " +
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
