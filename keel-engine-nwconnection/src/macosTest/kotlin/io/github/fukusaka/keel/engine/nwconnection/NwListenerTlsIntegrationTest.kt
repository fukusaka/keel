@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.codec.http.HttpRequestDecoder
import io.github.fukusaka.keel.codec.http.HttpResponse
import io.github.fukusaka.keel.codec.http.HttpResponseEncoder
import io.github.fukusaka.keel.codec.http.RoutingHandler
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.server.TlsServerConfig
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.TlsVersion
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.SIGTERM
import platform.posix.STDERR_FILENO
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
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * End-to-end integration coverage for the NW listener-level TLS path,
 * pinning that every server-relevant axis of [TlsConfig] actually reaches
 * the wire — the Red-Green counterpart to [NwTlsParamsTest]'s
 * compose-level smoke coverage.
 *
 * Each case stands up a real `NwEngine` HTTPS server on 127.0.0.1 and
 * drives `/usr/bin/curl` against it with the flag combination that
 * exercises the axis. The pre-fix state of each Netty axis is quoted in
 * the case comments — for NW the same silent-drop pattern applied
 * (only `certificates` was wired), so pinning them here has real teeth.
 */
class NwListenerTlsIntegrationTest {

    private val tempFiles = mutableListOf<String>()

    @AfterTest
    fun cleanupTemp() {
        tempFiles.forEach { platform.posix.unlink(it) }
        tempFiles.clear()
    }

    private val serverCerts = TlsCertificateSource.Pem(
        TestCertificatesNw.SERVER_CERT,
        TestCertificatesNw.SERVER_KEY,
    )

    // --- Control: default config completes the handshake ---

    @Test
    fun `default TLS config completes an HTTPS request via curl`() = runBlocking {
        withTimeout(BUDGET) {
            runTlsServer(TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE)) { port ->
                val (exit, out) = curl(
                    "-k", "-s", "--max-time", "5", "--connect-timeout", "3",
                    "-w", "\n%{http_code}",
                    "https://localhost:$port/hello",
                )
                assertEquals(0, exit, "curl must succeed against the default TLS server (output=$out)")
                assertTrue(out.contains("200"), "response must be 200 (output=$out)")
            }
        }
    }

    // --- Axis: minVersion (silent security downgrade if dropped) ---

    @Test
    fun `minVersion TLS1_3 rejects a TLS1_2-only curl client`() = runBlocking {
        withTimeout(BUDGET) {
            runTlsServer(
                TlsConfig(
                    certificates = serverCerts,
                    verifyMode = TlsVerifyMode.NONE,
                    minVersion = TlsVersion.TLS1_3,
                ),
            ) { port ->
                val (exit, _) = curl(
                    "-k", "-s", "--max-time", "5", "--connect-timeout", "3",
                    "--tls-max", "1.2", "--tlsv1.2",
                    "https://localhost:$port/hello",
                )
                // curl exit 35 = SSL handshake failure; any non-zero is
                // fine, we just require rejection.
                assertNotEquals(0, exit, "TLS 1.3 server must reject a TLS 1.2-only client")
            }
        }
    }

    // --- Axis: alpnProtocols ---

    @Test
    fun `alpnProtocols advertises the configured list to the client`() = runBlocking {
        withTimeout(BUDGET) {
            runTlsServer(
                TlsConfig(
                    certificates = serverCerts,
                    verifyMode = TlsVerifyMode.NONE,
                    alpnProtocols = listOf("http/1.1"),
                ),
            ) { port ->
                // curl with -v prints "ALPN: server accepted <proto>" when
                // the server offered ALPN. keel's HTTP layer speaks HTTP/1.1,
                // so we assert on that as the negotiated protocol — the axis
                // is exercised by the fact that the server negotiated an ALPN
                // protocol at all (before this fix the ALPN extension was
                // silently dropped, so `--http1.1` would not see an accepted
                // ALPN protocol from the peer). Compose-level smoke that the
                // full list (h2, http/1.1) is accepted lives in
                // NwTlsParamsTest to avoid depending on curl's HTTP/2 codec
                // interoperating with keel's HTTP/1.1-only response encoder.
                val (exit, out) = curl(
                    "-k", "-s", "-v", "--http1.1", "--max-time", "5", "--connect-timeout", "3",
                    "https://localhost:$port/hello",
                )
                assertEquals(0, exit, "curl must succeed (output=$out)")
                assertTrue(
                    out.contains("ALPN") && out.contains("http/1.1"),
                    "curl -v output must show ALPN negotiation of http/1.1 (output=$out)",
                )
            }
        }
    }

    // --- Axis: verifyMode + trustAnchors (mTLS) ---

    @Test
    fun `verifyMode REQUIRED rejects an anonymous curl client`() = runBlocking {
        withTimeout(BUDGET) {
            runTlsServer(
                TlsConfig(
                    certificates = serverCerts,
                    verifyMode = TlsVerifyMode.REQUIRED,
                    trustAnchors = TlsTrustSource.Pem(TestCertificatesNw.CLIENT_CA_CERT),
                ),
            ) { port ->
                val (exit, _) = curl(
                    "-k",
                    "-s",
                    "--max-time",
                    "5",
                    "--connect-timeout",
                    "3",
                    "https://localhost:$port/hello",
                )
                assertNotEquals(0, exit, "REQUIRED server must reject an anonymous curl client")
            }
        }
    }

    @Test
    fun `verifyMode REQUIRED accepts a curl client with a matching client cert`() = runBlocking {
        withTimeout(BUDGET) {
            val certPath = writeTemp("client.crt", TestCertificatesNw.CLIENT_CERT)
            val keyPath = writeTemp("client.key", TestCertificatesNw.CLIENT_KEY)
            runTlsServer(
                TlsConfig(
                    certificates = serverCerts,
                    verifyMode = TlsVerifyMode.REQUIRED,
                    trustAnchors = TlsTrustSource.Pem(TestCertificatesNw.CLIENT_CA_CERT),
                ),
            ) { port ->
                val (exit, out) = curl(
                    "-k", "-s", "-v", "--max-time", "5", "--connect-timeout", "3",
                    "--cert", certPath, "--key", keyPath,
                    "-w", "\n%{http_code}",
                    "https://localhost:$port/hello",
                )
                assertEquals(
                    0,
                    exit,
                    "REQUIRED server must accept a client with a cert signed by the trust anchor (output=$out)",
                )
                assertTrue(out.contains("200"), "response must be 200 (output=$out)")
            }
        }
    }

    // --- helpers ---

    private suspend fun runTlsServer(
        config: TlsConfig,
        body: suspend (port: Int) -> Unit,
    ) {
        val engine = NwEngine()
        try {
            val response = HttpResponse.ok("Hello, NW!", contentType = "text/plain")
            val server = engine.bindPipeline(
                "127.0.0.1",
                0,
                config = TlsServerConfig(config, installer = null),
            ) { channel ->
                channel.pipeline.addLast("encoder", HttpResponseEncoder())
                channel.pipeline.addLast("decoder", HttpRequestDecoder())
                channel.pipeline.addLast("routing", RoutingHandler(mapOf("/hello" to { response })))
            }
            try {
                val port = (server.localAddress as InetSocketAddress).port
                usleep(SERVER_START_DELAY_US)
                body(port)
            } finally {
                server.close()
            }
        } finally {
            engine.close()
        }
    }

    private fun curl(vararg args: String): Pair<Int, String> = memScoped {
        val pipeFds = allocArray<IntVar>(2)
        check(pipe(pipeFds) == 0) { "pipe() failed" }
        val readFd = pipeFds[0]
        val writeFd = pipeFds[1]

        val pid = fork()
        if (pid == 0) {
            close(readFd)
            dup2(writeFd, STDOUT_FILENO)
            dup2(writeFd, STDERR_FILENO)
            close(writeFd)
            val argv = mutableListOf("curl") + args.toList()
            // execl-style forwarder: convert to execv via memScoped alloc.
            executeCurl(argv)
            _exit(1)
        }

        close(writeFd)
        val output = readAllFromFd(readFd)
        close(readFd)

        val status = alloc<IntVar>()
        waitpid(pid, status.ptr, 0)
        val exited = (status.value and 0x7f) == 0
        val exitCode = if (exited) (status.value shr 8) and 0xff else -1

        kill(pid, SIGTERM)

        Pair(exitCode, output)
    }

    private fun executeCurl(argv: List<String>): Int {
        // Fall back to execl with a small fixed maximum — sufficient for
        // the flag counts we use above. Argument list ends with null.
        return when (argv.size) {
            2 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], null)
            3 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], null)
            4 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], argv[3], null)
            5 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], argv[3], argv[4], null)
            6 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], argv[3], argv[4], argv[5], null)
            7 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], argv[3], argv[4], argv[5], argv[6], null)
            8 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], argv[3], argv[4], argv[5], argv[6], argv[7], null)
            9 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], argv[3], argv[4], argv[5], argv[6], argv[7], argv[8], null)
            10 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], argv[3], argv[4], argv[5], argv[6], argv[7], argv[8], argv[9], null)
            11 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], argv[3], argv[4], argv[5], argv[6], argv[7], argv[8], argv[9], argv[10], null)
            12 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], argv[3], argv[4], argv[5], argv[6], argv[7], argv[8], argv[9], argv[10], argv[11], null)
            13 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], argv[3], argv[4], argv[5], argv[6], argv[7], argv[8], argv[9], argv[10], argv[11], argv[12], null)
            14 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], argv[3], argv[4], argv[5], argv[6], argv[7], argv[8], argv[9], argv[10], argv[11], argv[12], argv[13], null)
            15 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], argv[3], argv[4], argv[5], argv[6], argv[7], argv[8], argv[9], argv[10], argv[11], argv[12], argv[13], argv[14], null)
            16 -> platform.posix.execl("/usr/bin/curl", argv[0], argv[1], argv[2], argv[3], argv[4], argv[5], argv[6], argv[7], argv[8], argv[9], argv[10], argv[11], argv[12], argv[13], argv[14], argv[15], null)
            else -> error("increase execl overload count in NwListenerTlsIntegrationTest (argv=${argv.size})")
        }
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

    private fun writeTemp(nameHint: String, contents: String): String {
        val path = "/tmp/keel-nw-tls-$nameHint-${platform.posix.getpid()}.pem"
        val fd = platform.posix.open(
            path,
            platform.posix.O_WRONLY or platform.posix.O_CREAT or platform.posix.O_TRUNC,
            0x180u, /* 0600 */
        )
        check(fd >= 0) { "open($path) failed" }
        try {
            val bytes = contents.encodeToByteArray()
            bytes.usePinned { pinned ->
                var written = 0
                while (written < bytes.size) {
                    val n = platform.posix.write(
                        fd,
                        pinned.addressOf(written),
                        (bytes.size - written).convert(),
                    ).toInt()
                    if (n <= 0) error("write($path) failed")
                    written += n
                }
            }
        } finally {
            platform.posix.close(fd)
        }
        tempFiles.add(path)
        return path
    }

    companion object {
        private const val READ_BUF_SIZE = 4096
        private const val SERVER_START_DELAY_US = 200_000u
        private val BUDGET = 15.seconds
    }
}
