package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.TlsVersion
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * End-to-end integration coverage for the Node.js listener-level TLS
 * options, pinning that every server-relevant axis of [TlsConfig]
 * actually reaches the wire — the Red-Green counterpart to
 * [NodeTlsOptionsTest]'s compose-level smoke.
 *
 * Each case builds the [NodeTlsOptions.build] output for the axis under
 * test, hands it directly to `tls.createServer(...)` (bypassing
 * [NodeEngine] itself — the engine layer just delegates to
 * [NodeTlsOptions], so the axis wiring under test is complete without
 * standing up the whole engine), replies with a canned HTTP/1.1 200
 * response, and drives `child_process.spawn("curl", ...)` against it.
 *
 * Pre-fix the same silent-drop pattern applied here as to the Netty /
 * NW backends (only `key` / `cert` were wired); stripping the axis
 * writes from [NodeTlsOptions] flips the corresponding case to FAIL
 * with the expected pre-fix symptoms.
 */
class NodeListenerTlsIntegrationTest {

    private val serverCerts = TlsCertificateSource.Pem(
        NodeTestCertificates.SERVER_CERT,
        NodeTestCertificates.SERVER_KEY,
    )

    // --- Control ---

    @Test
    fun default_TLS_config_completes_an_HTTPS_request_via_curl(): Promise<Unit> {
        return withTlsServer(TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE)) { port ->
            curl(
                arrayOf(
                    "-k", "-s", "--max-time", "5", "--connect-timeout", "3",
                    "-w", "\n%{http_code}", "https://localhost:$port/hello",
                ),
            )
        }.then { (exit, out) ->
            assertEquals(0, exit, "curl must succeed against the default TLS server (output=$out)")
            assertTrue(out.contains("200"), "response must be 200 (output=$out)")
        }
    }

    // --- Axis: minVersion (silent security downgrade guard) ---

    @Test
    fun minVersion_TLS1_3_rejects_a_TLS1_2_only_curl_client(): Promise<Unit> {
        return withTlsServer(
            TlsConfig(
                certificates = serverCerts,
                verifyMode = TlsVerifyMode.NONE,
                minVersion = TlsVersion.TLS1_3,
            ),
        ) { port ->
            curl(
                arrayOf(
                    "-k", "-s", "--max-time", "5", "--connect-timeout", "3",
                    "--tls-max", "1.2", "--tlsv1.2", "https://localhost:$port/hello",
                ),
            )
        }.then { (exit, _) ->
            assertNotEquals(0, exit, "TLS 1.3 server must reject a TLS 1.2-only client")
        }
    }

    // --- Axis: alpnProtocols ---

    @Test
    fun alpnProtocols_advertises_the_configured_list_to_the_client(): Promise<Unit> {
        return withTlsServer(
            TlsConfig(
                certificates = serverCerts,
                verifyMode = TlsVerifyMode.NONE,
                alpnProtocols = listOf("http/1.1"),
            ),
        ) { port ->
            curl(
                arrayOf(
                    "-k", "-s", "-v", "--http1.1", "--max-time", "5", "--connect-timeout", "3",
                    "https://localhost:$port/hello",
                ),
            )
        }.then { (exit, out) ->
            assertEquals(0, exit, "curl must succeed (output=$out)")
            assertTrue(
                out.contains("ALPN") && out.contains("http/1.1"),
                "curl -v output must show ALPN negotiation of http/1.1 (output=$out)",
            )
        }
    }

    // --- Axis: verifyMode + trustAnchors (mTLS) ---

    @Test
    fun verifyMode_REQUIRED_rejects_an_anonymous_curl_client(): Promise<Unit> {
        return withTlsServer(
            TlsConfig(
                certificates = serverCerts,
                verifyMode = TlsVerifyMode.REQUIRED,
                trustAnchors = TlsTrustSource.Pem(NodeTestCertificates.CLIENT_CA_CERT),
            ),
        ) { port ->
            curl(
                arrayOf(
                    "-k",
                    "-s",
                    "--max-time",
                    "5",
                    "--connect-timeout",
                    "3",
                    "https://localhost:$port/hello",
                ),
            )
        }.then { (exit, _) ->
            assertNotEquals(0, exit, "REQUIRED server must reject an anonymous curl client")
        }
    }

    @Test
    fun verifyMode_REQUIRED_accepts_a_client_with_a_matching_cert(): Promise<Unit> {
        val certPath = writeTemp("client.crt", NodeTestCertificates.CLIENT_CERT)
        val keyPath = writeTemp("client.key", NodeTestCertificates.CLIENT_KEY)
        return withTlsServer(
            TlsConfig(
                certificates = serverCerts,
                verifyMode = TlsVerifyMode.REQUIRED,
                trustAnchors = TlsTrustSource.Pem(NodeTestCertificates.CLIENT_CA_CERT),
            ),
        ) { port ->
            curl(
                arrayOf(
                    "-k", "-s", "--max-time", "5", "--connect-timeout", "3",
                    "--cert", certPath, "--key", keyPath,
                    "-w", "\n%{http_code}", "https://localhost:$port/hello",
                ),
            )
        }.then { (exit, out) ->
            unlink(certPath)
            unlink(keyPath)
            assertEquals(0, exit, "REQUIRED server must accept a matching client cert (output=$out)")
            assertTrue(out.contains("200"), "response must be 200 (output=$out)")
        }
    }

    // --- helpers ---

    /**
     * Stands up a bare `tls.createServer(NodeTlsOptions.build(config))`
     * on a random port, replies with a canned HTTP/1.1 200 to any TLS
     * connection, drives [run] with the port, then tears the server
     * down. Bypasses [NodeEngine] itself since the engine layer just
     * delegates to [NodeTlsOptions] — everything under test in this
     * file is the axis wiring in that helper.
     */
    private fun withTlsServer(
        config: TlsConfig,
        run: (port: Int) -> Promise<Pair<Int, String>>,
    ): Promise<Pair<Int, String>> {
        val tls = js("require('tls')")
        val options = NodeTlsOptions.build(config)
        val server = tls.createServer(options) { socket: dynamic ->
            socket.on("data") { _: dynamic ->
                val body = "Hello, Node!"
                val response = "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                socket.write(response)
                socket.end()
            }
            // Swallow TLS errors so a rejected handshake does not crash the process.
            socket.on("error") { _: dynamic -> }
        }
        // Same swallow at the server level (rejectUnauthorized emits
        // an `error` on `server` for the aborted handshake).
        server.on("error") { _: dynamic -> }
        server.on("tlsClientError") { _: dynamic, _: dynamic -> }

        return Promise { resolve, _ ->
            server.listen(0) {
                val port = server.address().port as Int
                run(port).then { result ->
                    server.close { resolve(result) }
                    Unit
                }
            }
        }
    }

    private fun curl(args: Array<String>): Promise<Pair<Int, String>> {
        val childProcess = js("require('child_process')")
        return Promise { resolve, _ ->
            val proc = childProcess.spawn("curl", args)
            val sb = StringBuilder()
            proc.stdout.on("data") { chunk: dynamic ->
                sb.append(chunk.toString("utf8") as String)
            }
            proc.stderr.on("data") { chunk: dynamic ->
                sb.append(chunk.toString("utf8") as String)
            }
            proc.on("close") { code: dynamic ->
                val exit = (code as? Int) ?: -1
                resolve(exit to sb.toString())
            }
            proc.on("error") { _: dynamic ->
                resolve(-1 to sb.toString())
            }
        }
    }

    private fun writeTemp(nameHint: String, contents: String): String {
        val fs = js("require('fs')")
        val os = js("require('os')")
        val path = js("require('path')")
        val tmpDir = os.tmpdir() as String
        val pid = js("process.pid") as Int
        val file = path.join(tmpDir, "keel-node-tls-$nameHint-$pid.pem") as String
        fs.writeFileSync(file, contents, js("{ mode: 0o600 }"))
        return file
    }

    private fun unlink(path: String) {
        try {
            js("require('fs')").unlinkSync(path)
        } catch (_: Throwable) {
            // best-effort cleanup
        }
    }
}
