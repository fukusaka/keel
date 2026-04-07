package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.engine.netty.NettyPipelinedChannel
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsConfig
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import java.io.ByteArrayInputStream
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory

/**
 * [TlsInstaller] that uses Netty's native [SslHandler][io.netty.handler.ssl.SslHandler]
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
 * Usage:
 * ```
 * embeddedServer(Keel) {
 *     sslConnector(tlsConfig, JsseTlsCodecFactory(), NettySslInstaller()) {
 *         port = 8443
 *     }
 * }
 * ```
 */
class NettySslInstaller : TlsInstaller {

    override fun install(channel: PipelinedChannel, config: TlsConfig) {
        require(channel is NettyPipelinedChannel) {
            "NettySslInstaller requires NettyPipelinedChannel, got ${channel::class.simpleName}"
        }
        val certs = requireNotNull(config.certificates) {
            "TlsConfig.certificates must be set for NettySslInstaller"
        }
        val sslContext = when (certs) {
            is TlsCertificateSource.Pem -> buildFromPem(certs)
            is TlsCertificateSource.KeyStoreFile -> buildFromKeyStore(certs)
            else -> error("NettySslInstaller supports Pem and KeyStoreFile, got ${certs::class.simpleName}")
        }
        channel.installSslHandler(sslContext)
    }

    private fun buildFromPem(pem: TlsCertificateSource.Pem): SslContext {
        val certStream = ByteArrayInputStream(pem.certificatePem.toByteArray())
        val keyStream = ByteArrayInputStream(pem.privateKeyPem.toByteArray())
        return SslContextBuilder.forServer(certStream, keyStream).build()
    }

    private fun buildFromKeyStore(ks: TlsCertificateSource.KeyStoreFile): SslContext {
        val keyStore = KeyStore.getInstance(ks.type)
        java.io.FileInputStream(ks.path).use { fis ->
            keyStore.load(fis, ks.password.toCharArray())
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, ks.password.toCharArray())
        return SslContextBuilder.forServer(kmf).build()
    }
}
