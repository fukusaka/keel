package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.TlsVersion
import io.netty.buffer.ByteBuf
import io.netty.buffer.PooledByteBufAllocator
import io.netty.channel.embedded.EmbeddedChannel
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslHandler
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins that [NettySslInstaller] honours every server-relevant axis of
 * [TlsConfig] — the axes that used to be dropped silently:
 * [TlsConfig.alpnProtocols] / [TlsConfig.verifyMode] /
 * [TlsConfig.minVersion] + [TlsConfig.maxVersion] /
 * [TlsConfig.trustAnchors].
 *
 * Rather than open a real listener, each case wires the installer's
 * server [io.netty.handler.ssl.SslContext] and a peer client
 * `SslContext` into a pair of [EmbeddedChannel]s, then shuttles the
 * outbound TLS records into the other side's `writeInbound(...)` until
 * both handshakes complete (or one aborts). Fully deterministic — no
 * kernel, no timing.
 *
 * The dropped axes were **security-relevant**:
 * [TlsConfig.minVersion] = TLS 1.3 must actually forbid a TLS 1.2 peer
 * (until this change the JDK default range was silently used, so a
 * peer that only spoke TLS 1.2 could still connect); mTLS
 * `verifyMode = REQUIRED` must actually reject a client that presents
 * no certificate; a `trustAnchors = Pem(...)` scope must actually
 * reject a client cert rooted elsewhere.
 */
class NettySslInstallerTlsConfigTest {

    private val installer = NettySslInstaller()
    private val allocator = PooledByteBufAllocator(false)

    private val serverCerts = TlsCertificateSource.Pem(
        TestCertificates.SERVER_CERT,
        TestCertificates.SERVER_KEY,
    )

    // --- happy path (control) ---

    @Test
    fun `default config completes the handshake`() {
        val server = installer.buildSslContext(
            TlsConfig(certificates = serverCerts, verifyMode = TlsVerifyMode.NONE),
        )
        val clientCtx = SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .build()

        val serverCh = EmbeddedChannel(server.newHandler(allocator))
        val clientCh = EmbeddedChannel(clientCtx.newHandler(allocator))
        driveHandshake(client = clientCh, server = serverCh)

        assertTrue(serverCh.sslHandler().handshakeFuture().isSuccess, "server handshake must complete")
        assertTrue(clientCh.sslHandler().handshakeFuture().isSuccess, "client handshake must complete")

        clientCh.close()
        serverCh.close()
    }

    // --- alpnProtocols ---

    @Test
    fun `alpnProtocols negotiates a shared protocol`() {
        val server = installer.buildSslContext(
            TlsConfig(
                certificates = serverCerts,
                verifyMode = TlsVerifyMode.NONE,
                alpnProtocols = listOf("h2", "http/1.1"),
            ),
        )
        val clientCtx = SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .applicationProtocolConfig(
                ApplicationProtocolConfig(
                    Protocol.ALPN,
                    SelectorFailureBehavior.NO_ADVERTISE,
                    SelectedListenerFailureBehavior.ACCEPT,
                    "h2",
                ),
            )
            .build()

        val serverCh = EmbeddedChannel(server.newHandler(allocator))
        val clientCh = EmbeddedChannel(clientCtx.newHandler(allocator))
        driveHandshake(client = clientCh, server = serverCh)

        assertTrue(serverCh.sslHandler().handshakeFuture().isSuccess)
        assertEquals(
            "h2",
            serverCh.sslHandler().applicationProtocol(),
            "server must negotiate the shared ALPN protocol — before this fix alpnProtocols was dropped",
        )

        clientCh.close()
        serverCh.close()
    }

    // --- minVersion / maxVersion ---

    @Test
    fun `minVersion TLS1_3 rejects a TLS1_2-only client`() {
        // Before this fix Netty inherited the JDK's default protocol range
        // (which includes TLS 1.2), so pinning minVersion = TLS 1.3 had
        // no effect — a security-critical downgrade.
        val server = installer.buildSslContext(
            TlsConfig(
                certificates = serverCerts,
                verifyMode = TlsVerifyMode.NONE,
                minVersion = TlsVersion.TLS1_3,
            ),
        )
        val clientCtx = SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .protocols("TLSv1.2")
            .build()

        val serverCh = EmbeddedChannel(server.newHandler(allocator))
        val clientCh = EmbeddedChannel(clientCtx.newHandler(allocator))
        val serverFuture = serverCh.sslHandler().handshakeFuture()
        driveHandshake(client = clientCh, server = serverCh, expectFailure = true)

        assertFalse(
            serverFuture.isSuccess,
            "server must reject a TLS 1.2 client when minVersion = TLS 1.3",
        )

        clientCh.close()
        serverCh.close()
    }

    // --- verifyMode (mTLS) ---

    @Test
    fun `verifyMode REQUIRED rejects a client that presents no certificate`() {
        // Before this fix verifyMode was dropped, so REQUIRED accepted an
        // anonymous client — mTLS silently degraded to server-auth only.
        val server = installer.buildSslContext(
            TlsConfig(
                certificates = serverCerts,
                verifyMode = TlsVerifyMode.REQUIRED,
                trustAnchors = TlsTrustSource.Pem(TestCertificates.CLIENT_CA_CERT),
            ),
        )
        val clientCtx = SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .build()

        val serverCh = EmbeddedChannel(server.newHandler(allocator))
        val clientCh = EmbeddedChannel(clientCtx.newHandler(allocator))
        val serverFuture = serverCh.sslHandler().handshakeFuture()
        driveHandshake(client = clientCh, server = serverCh, expectFailure = true)

        assertFalse(
            serverFuture.isSuccess,
            "server must reject an anonymous client when verifyMode = REQUIRED",
        )

        clientCh.close()
        serverCh.close()
    }

    @Test
    fun `verifyMode REQUIRED accepts a client whose cert is signed by the configured trust anchor`() {
        val server = installer.buildSslContext(
            TlsConfig(
                certificates = serverCerts,
                verifyMode = TlsVerifyMode.REQUIRED,
                trustAnchors = TlsTrustSource.Pem(TestCertificates.CLIENT_CA_CERT),
            ),
        )
        val clientCtx = SslContextBuilder.forClient()
            .keyManager(
                ByteArrayInputStream(TestCertificates.CLIENT_CERT.toByteArray()),
                ByteArrayInputStream(TestCertificates.CLIENT_KEY.toByteArray()),
            )
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .build()

        val serverCh = EmbeddedChannel(server.newHandler(allocator))
        val clientCh = EmbeddedChannel(clientCtx.newHandler(allocator))
        driveHandshake(client = clientCh, server = serverCh)

        assertTrue(serverCh.sslHandler().handshakeFuture().isSuccess)
        assertTrue(clientCh.sslHandler().handshakeFuture().isSuccess)

        clientCh.close()
        serverCh.close()
    }

    // --- trustAnchors scope ---

    @Test
    fun `trustAnchors rejects a client cert rooted elsewhere`() {
        // Server only trusts the client CA, but the client presents a
        // certificate signed by the *server's* self-signed root (i.e. the
        // server cert itself — an unrelated PKI). Must abort.
        val server = installer.buildSslContext(
            TlsConfig(
                certificates = serverCerts,
                verifyMode = TlsVerifyMode.REQUIRED,
                trustAnchors = TlsTrustSource.Pem(TestCertificates.CLIENT_CA_CERT),
            ),
        )
        val clientCtx = SslContextBuilder.forClient()
            // The server cert is self-signed with CN=localhost, so we hand
            // it back as a "client cert" that is not signed by the CA the
            // server actually trusts.
            .keyManager(
                ByteArrayInputStream(TestCertificates.SERVER_CERT.toByteArray()),
                ByteArrayInputStream(TestCertificates.SERVER_KEY.toByteArray()),
            )
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .build()

        val serverCh = EmbeddedChannel(server.newHandler(allocator))
        val clientCh = EmbeddedChannel(clientCtx.newHandler(allocator))
        val serverFuture = serverCh.sslHandler().handshakeFuture()
        driveHandshake(client = clientCh, server = serverCh, expectFailure = true)

        assertFalse(
            serverFuture.isSuccess,
            "server must reject a client cert rooted elsewhere",
        )

        clientCh.close()
        serverCh.close()
    }

    // --- KeyStoreFile cert × mTLS (the `forServer(KeyManagerFactory)` +
    //     `trustManager(...)` chain that Pem-based tests do not cover) ---

    @Test
    fun `KeyStoreFile cert + verifyMode REQUIRED accepts a valid client cert`() {
        val keyStorePath = writeServerKeyStore()
        val server = installer.buildSslContext(
            TlsConfig(
                certificates = TlsCertificateSource.KeyStoreFile(
                    keyStorePath.toString(),
                    KEYSTORE_PASSWORD,
                ),
                verifyMode = TlsVerifyMode.REQUIRED,
                trustAnchors = TlsTrustSource.Pem(TestCertificates.CLIENT_CA_CERT),
            ),
        )
        val clientCtx = SslContextBuilder.forClient()
            .keyManager(
                ByteArrayInputStream(TestCertificates.CLIENT_CERT.toByteArray()),
                ByteArrayInputStream(TestCertificates.CLIENT_KEY.toByteArray()),
            )
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .build()

        val serverCh = EmbeddedChannel(server.newHandler(allocator))
        val clientCh = EmbeddedChannel(clientCtx.newHandler(allocator))
        driveHandshake(client = clientCh, server = serverCh)

        assertTrue(
            serverCh.sslHandler().handshakeFuture().isSuccess,
            "KeyStoreFile-loaded server must complete the mTLS handshake",
        )
        assertTrue(clientCh.sslHandler().handshakeFuture().isSuccess)

        clientCh.close()
        serverCh.close()
    }

    @Test
    fun `KeyStoreFile cert + verifyMode REQUIRED rejects an anonymous client`() {
        // The failure-mode counterpart of the happy-path test above. Together
        // they pin that both branches of `NettySslInstaller.buildSslContext`
        // — `forServerFromPem` and `forServerFromKeyStore` — produce a
        // functionally equivalent `SslContext` when combined with a mTLS
        // trust manager. Before this the KeyStoreFile + trustManager combo
        // was only reasoned about, not exercised.
        val keyStorePath = writeServerKeyStore()
        val server = installer.buildSslContext(
            TlsConfig(
                certificates = TlsCertificateSource.KeyStoreFile(
                    keyStorePath.toString(),
                    KEYSTORE_PASSWORD,
                ),
                verifyMode = TlsVerifyMode.REQUIRED,
                trustAnchors = TlsTrustSource.Pem(TestCertificates.CLIENT_CA_CERT),
            ),
        )
        val clientCtx = SslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .build()

        val serverCh = EmbeddedChannel(server.newHandler(allocator))
        val clientCh = EmbeddedChannel(clientCtx.newHandler(allocator))
        val serverFuture = serverCh.sslHandler().handshakeFuture()
        driveHandshake(client = clientCh, server = serverCh, expectFailure = true)

        assertFalse(
            serverFuture.isSuccess,
            "KeyStoreFile-loaded server must still reject an anonymous client under REQUIRED",
        )

        clientCh.close()
        serverCh.close()
    }

    // --- helpers ---

    /**
     * Writes a PKCS12 KeyStore containing the server's cert + private key
     * to a temp file and tracks it in [tempKeyStores] for cleanup in
     * [tearDown]. The KeyStoreFile fixture the installer needs is
     * synthesised from the same PEM material the Pem-based cases use, so
     * the two branches are exercised against the same identity — any
     * behavioural drift shows up as an assertion divergence between the
     * `Pem` and `KeyStoreFile` tests.
     */
    private fun writeServerKeyStore(): Path {
        val certFactory = CertificateFactory.getInstance("X.509")
        val serverCert = certFactory.generateCertificate(
            ByteArrayInputStream(TestCertificates.SERVER_CERT.toByteArray()),
        ) as X509Certificate

        val pkcs8Bytes = pemToDer(TestCertificates.SERVER_KEY)
        val privateKey = KeyFactory.getInstance("RSA")
            .generatePrivate(PKCS8EncodedKeySpec(pkcs8Bytes))

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            "server",
            privateKey,
            KEYSTORE_PASSWORD.toCharArray(),
            arrayOf(serverCert),
        )

        val path = Files.createTempFile("keel-netty-tls-", ".p12")
        tempKeyStores.add(path)
        Files.newOutputStream(path).use { out ->
            keyStore.store(out, KEYSTORE_PASSWORD.toCharArray())
        }
        return path
    }

    private fun pemToDer(pem: String): ByteArray {
        val base64 = pem.lineSequence()
            .filter { !it.startsWith("-----") && it.isNotBlank() }
            .joinToString("")
        return Base64.getDecoder().decode(base64)
    }

    private val tempKeyStores = mutableListOf<Path>()

    @AfterTest
    fun cleanupKeyStores() {
        tempKeyStores.forEach { Files.deleteIfExists(it) }
        tempKeyStores.clear()
    }

    /**
     * Shuttles outbound TLS records between the two peers' embedded
     * channels until both handshake futures complete (success or failure)
     * or the round budget is exhausted (defence against a broken pump).
     */
    private fun driveHandshake(
        client: EmbeddedChannel,
        server: EmbeddedChannel,
        expectFailure: Boolean = false,
        rounds: Int = 64,
    ) {
        // Take the handshake futures up front — once a peer aborts, its
        // SslHandler is removed from the pipeline, and `pipeline().get(...)`
        // would then return null.
        val clientFuture = client.sslHandler().handshakeFuture()
        val serverFuture = server.sslHandler().handshakeFuture()
        var remaining = rounds
        while (remaining-- > 0) {
            if (clientFuture.isDone && serverFuture.isDone) return

            val progress =
                drain(client, into = server) or drain(server, into = client)
            if (!progress) {
                if (expectFailure) return
                error("handshake stalled with neither side complete")
            }
        }
        if (!expectFailure) error("handshake did not converge within $rounds rounds")
    }

    private fun drain(from: EmbeddedChannel, into: EmbeddedChannel): Boolean {
        var any = false
        while (true) {
            val record: ByteBuf = from.readOutbound() ?: break
            // A rejected peer surfaces the reason as a DecoderException
            // when its own SslHandler decodes the fatal alert record we
            // just forwarded. Absorb it — the handshake future on that
            // side will already have been marked as failed, which is what
            // the assertions consult.
            try {
                into.writeInbound(record)
            } catch (_: io.netty.handler.codec.DecoderException) {
                // expected on the abort path
            }
            any = true
        }
        return any
    }

    private fun EmbeddedChannel.sslHandler(): SslHandler {
        val h = pipeline().get(SslHandler::class.java)
        return assertNotNull(h, "channel must have an SslHandler installed")
    }

    private companion object {
        private const val KEYSTORE_PASSWORD = "keel-test"
    }
}
