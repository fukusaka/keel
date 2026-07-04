package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.server.TlsServerInstaller
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.TlsVersion
import io.github.fukusaka.keel.tls.asPem
import io.netty.handler.ssl.ApplicationProtocolConfig
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior
import io.netty.handler.ssl.ClientAuth
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.TrustManagerFactory

/**
 * [TlsServerInstaller] that uses Netty's native [SslHandler][io.netty.handler.ssl.SslHandler]
 * instead of keel's [TlsHandler][io.github.fukusaka.keel.tls.TlsHandler].
 *
 * Installs Netty's `SslHandler` in the Netty pipeline (before the keel
 * handler), so decryption happens at the Netty transport level. The keel
 * pipeline receives plaintext — no keel `TlsHandler` is needed.
 *
 * ```
 * Netty pipeline:  SslHandler → keel handler (channelRead → IoBuf copy)
 * keel pipeline:   HEAD → ... → TAIL   (no TlsHandler)
 * ```
 *
 * Supports [TlsCertificateSource.Pem] and [TlsCertificateSource.KeyStoreFile].
 *
 * **Honored [TlsConfig] axes on the server side**:
 *  * [TlsConfig.certificates] via `SslContextBuilder.forServer(...)`
 *  * [TlsConfig.trustAnchors] via `SslContextBuilder.trustManager(...)`
 *  * [TlsConfig.verifyMode] via `SslContextBuilder.clientAuth(...)` (mTLS)
 *  * [TlsConfig.alpnProtocols] via `SslContextBuilder.applicationProtocolConfig(...)`
 *  * [TlsConfig.minVersion] / [TlsConfig.maxVersion] via
 *    `SslContextBuilder.protocols(...)` — the version floor is always
 *    enforced (defaults to TLS 1.2, so SSLv3 / TLS 1.0 / TLS 1.1 are
 *    never enabled even when the JDK would accept them).
 *
 * Client-side axes ([TlsConfig.serverName] / [TlsConfig.verifyHostname])
 * are not consumed here — this is a server installer.
 *
 * Usage:
 * ```
 * embeddedServer(Keel) {
 *     engine = NettyEngine(IoEngineConfig())
 *     sslConnector(tlsConfig, NettySslInstaller()) { port = 8443 }
 * }
 * ```
 */
class NettySslInstaller : TlsServerInstaller {

    override fun install(channel: PipelinedChannel, config: TlsConfig) {
        require(channel is NettyPipelinedChannel) {
            "NettySslInstaller requires NettyPipelinedChannel, got ${channel::class.simpleName}"
        }
        val sslContext = buildSslContext(config)
        (channel.transport as NettyIoTransport).installSslHandler(sslContext)
    }

    /**
     * Builds a server-mode [SslContext] honoring every server-relevant axis
     * of [TlsConfig]. Exposed for testing so a pair of `SslHandler`s can be
     * driven against each other in an `EmbeddedChannel` pipe without
     * touching the real transport.
     */
    internal fun buildSslContext(config: TlsConfig): SslContext {
        val certs = requireNotNull(config.certificates) {
            "TlsConfig.certificates must be set for NettySslInstaller"
        }
        val builder = when (certs) {
            is TlsCertificateSource.Pem -> forServerFromPem(certs)
            is TlsCertificateSource.Der -> forServerFromPem(certs.asPem())
            is TlsCertificateSource.KeyStoreFile -> forServerFromKeyStore(certs)
            is TlsCertificateSource.SystemKeychain ->
                error("SystemKeychain is not supported by NettySslInstaller")
        }

        applyTrustAnchors(builder, config.trustAnchors)
        builder.clientAuth(clientAuthFor(config.verifyMode))
        applyAlpn(builder, config.alpnProtocols)
        builder.protocols(*protocolsFor(config))

        return builder.build()
    }

    private fun forServerFromPem(pem: TlsCertificateSource.Pem): SslContextBuilder {
        val certStream = ByteArrayInputStream(pem.certificatePem.toByteArray())
        val keyStream = ByteArrayInputStream(pem.privateKeyPem.toByteArray())
        return SslContextBuilder.forServer(certStream, keyStream)
    }

    private fun forServerFromKeyStore(ks: TlsCertificateSource.KeyStoreFile): SslContextBuilder {
        val keyStore = KeyStore.getInstance(ks.type)
        java.io.FileInputStream(ks.path).use { fis ->
            keyStore.load(fis, ks.password.toCharArray())
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, ks.password.toCharArray())
        return SslContextBuilder.forServer(kmf)
    }

    /**
     * Wires [TlsConfig.trustAnchors] into [SslContextBuilder]. Only relevant
     * when the server also validates a client certificate (mTLS —
     * [TlsConfig.verifyMode] other than [TlsVerifyMode.NONE]); leaving it
     * `null` inherits the JDK system trust store.
     */
    private fun applyTrustAnchors(builder: SslContextBuilder, trust: TlsTrustSource?) {
        when (trust) {
            null,
            is TlsTrustSource.SystemDefault -> {
                // Nothing to configure — SslContextBuilder inherits the JDK
                // default TrustManagerFactory when trustManager(...) is not
                // called.
            }
            is TlsTrustSource.Pem -> {
                val tmf = trustManagerFactoryFromPem(trust.caPem)
                builder.trustManager(tmf)
            }
            is TlsTrustSource.InsecureTrustAll -> {
                builder.trustManager(InsecureTrustManagerFactory.INSTANCE)
            }
        }
    }

    private fun trustManagerFactoryFromPem(pem: String): TrustManagerFactory {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType())
        ks.load(null, null)
        val cf = CertificateFactory.getInstance("X.509")
        val certs = cf.generateCertificates(ByteArrayInputStream(pem.toByteArray()))
        certs.forEachIndexed { i, cert ->
            ks.setCertificateEntry("ca-$i", cert as X509Certificate)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(ks)
        return tmf
    }

    private fun applyAlpn(builder: SslContextBuilder, protocols: List<String>?) {
        if (protocols.isNullOrEmpty()) return
        builder.applicationProtocolConfig(
            ApplicationProtocolConfig(
                Protocol.ALPN,
                SelectorFailureBehavior.NO_ADVERTISE,
                SelectedListenerFailureBehavior.ACCEPT,
                protocols,
            ),
        )
    }

    companion object {
        private fun clientAuthFor(mode: TlsVerifyMode): ClientAuth = when (mode) {
            TlsVerifyMode.NONE -> ClientAuth.NONE
            TlsVerifyMode.PEER -> ClientAuth.OPTIONAL
            TlsVerifyMode.REQUIRED -> ClientAuth.REQUIRE
        }

        /**
         * Enumerates the JSSE protocol names allowed by [TlsConfig]'s
         * `minVersion..maxVersion` range. Always non-empty because
         * [TlsConfig] validates the range at construction. Applying the
         * result via `SslContextBuilder.protocols(...)` overrides any
         * broader JDK default (this is how the security-critical
         * `minVersion = TLS1_2` floor is actually enforced instead of
         * silently downgrading to whatever the JDK enables).
         */
        private fun protocolsFor(config: TlsConfig): Array<String> {
            val max = config.maxVersion ?: TlsVersion.TLS1_3
            return TlsVersion.entries
                .filter { it >= config.minVersion && it <= max }
                .map { jsseProtocolName(it) }
                .toTypedArray()
        }

        private fun jsseProtocolName(version: TlsVersion): String = when (version) {
            TlsVersion.TLS1_2 -> "TLSv1.2"
            TlsVersion.TLS1_3 -> "TLSv1.3"
        }
    }
}
