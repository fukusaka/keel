package io.github.fukusaka.keel.tls.jsse

import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.security.KeyFactory
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Security
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.ManagerFactoryParameters
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.TrustManagerFactorySpi
import javax.net.ssl.X509TrustManager

/**
 * [TlsCodecFactory] implementation backed by JSSE [SSLContext].
 *
 * Creates [JsseTlsCodec] instances that wrap [SSLEngine] for TLS
 * record protection. A new [SSLContext] is built for each [TlsConfig]
 * (future optimization: cache SSLContext per config).
 */
class JsseTlsCodecFactory : TlsCodecFactory {

    override fun createServerCodec(config: TlsConfig): TlsCodec {
        val sslContext = buildSslContext(config)
        val engine = sslContext.createSSLEngine()
        engine.useClientMode = false
        configureVerification(engine, config)
        configureAlpn(engine, config)
        return JsseTlsCodec(engine)
    }

    /**
     * Creates a client-mode [SSLEngine] with optional SNI and ALPN.
     *
     * When [TlsConfig.serverName] is set, it is passed to
     * [SSLContext.createSSLEngine] as the peer host hint (enables
     * certificate hostname verification) and configured as an SNI
     * extension via [SSLEngine.getSSLParameters].
     */
    override fun createClientCodec(config: TlsConfig): TlsCodec {
        val sslContext = buildSslContext(config)
        val engine = if (config.serverName != null) {
            sslContext.createSSLEngine(config.serverName, NO_PORT_HINT)
        } else {
            sslContext.createSSLEngine()
        }
        engine.useClientMode = true
        configureAlpn(engine, config)
        configureSni(engine, config)
        return JsseTlsCodec(engine)
    }

    override fun close() {
        // SSLContext is GC-managed; no explicit cleanup needed.
    }

    private fun buildSslContext(config: TlsConfig): SSLContext {
        val kmf = config.certificates?.let { buildKeyManagerFactory(it) }
        val tmf = config.trustAnchors?.let { buildTrustManagerFactory(it) }

        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            kmf?.keyManagers,
            tmf?.trustManagers,
            null,
        )
        return sslContext
    }

    private fun buildKeyManagerFactory(source: TlsCertificateSource): KeyManagerFactory {
        val ks = KeyStore.getInstance(KEYSTORE_TYPE)

        when (source) {
            is TlsCertificateSource.Pem -> {
                ks.load(null, null)
                val certChain = parsePemCertificates(source.certificatePem)
                val privateKey = parsePemPrivateKey(source.privateKeyPem)
                ks.setKeyEntry(KEY_ALIAS, privateKey, charArrayOf(), certChain)
            }
            is TlsCertificateSource.KeyStoreFile -> {
                FileInputStream(source.path).use { fis ->
                    val store = KeyStore.getInstance(source.type)
                    store.load(fis, source.password.toCharArray())
                    // Return KMF directly from the loaded store.
                    val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
                    kmf.init(store, source.password.toCharArray())
                    return kmf
                }
            }
            is TlsCertificateSource.Der -> {
                ks.load(null, null)
                val cf = CertificateFactory.getInstance(X509_CERT_TYPE)
                val cert = cf.generateCertificate(ByteArrayInputStream(source.certificate))
                // Only RSA keys are supported; EC key support requires
                // algorithm detection from the PKCS#8 structure.
                val kf = KeyFactory.getInstance(RSA_ALGORITHM)
                val privateKey = kf.generatePrivate(PKCS8EncodedKeySpec(source.privateKey))
                ks.setKeyEntry(KEY_ALIAS, privateKey, charArrayOf(), arrayOf(cert))
            }
            is TlsCertificateSource.SystemKeychain ->
                error("SystemKeychain is not supported on JVM")
        }

        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, charArrayOf())
        return kmf
    }

    private fun buildTrustManagerFactory(source: TlsTrustSource): TrustManagerFactory {
        return when (source) {
            is TlsTrustSource.SystemDefault -> {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(null as KeyStore?)
                tmf
            }
            is TlsTrustSource.Pem -> {
                val ks = KeyStore.getInstance(KEYSTORE_TYPE)
                ks.load(null, null)
                val certs = parsePemCertificates(source.caPem)
                certs.forEachIndexed { i, cert ->
                    ks.setCertificateEntry("ca-$i", cert)
                }
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                tmf.init(ks)
                tmf
            }
            is TlsTrustSource.InsecureTrustAll -> {
                // Return a TMF-compatible wrapper for the trust-all manager.
                val insecureTm = InsecureTrustAllManager()
                InsecureTrustManagerFactory(insecureTm)
            }
        }
    }

    private fun configureVerification(engine: SSLEngine, config: TlsConfig) {
        when (config.verifyMode) {
            TlsVerifyMode.NONE -> {
                engine.needClientAuth = false
                engine.wantClientAuth = false
            }
            TlsVerifyMode.PEER -> {
                engine.wantClientAuth = true
            }
            TlsVerifyMode.REQUIRED -> {
                engine.needClientAuth = true
            }
        }
    }

    private fun configureAlpn(engine: SSLEngine, config: TlsConfig) {
        val protocols = config.alpnProtocols ?: return
        val params = engine.sslParameters
        params.applicationProtocols = protocols.toTypedArray()
        engine.sslParameters = params
    }

    private fun configureSni(engine: SSLEngine, config: TlsConfig) {
        val name = config.serverName ?: return
        val params = engine.sslParameters
        params.serverNames = listOf(SNIHostName(name))
        engine.sslParameters = params
    }

    companion object {
        private const val KEYSTORE_TYPE = "PKCS12"
        private const val KEY_ALIAS = "server"
        private const val X509_CERT_TYPE = "X.509"

        // Only RSA keys are supported; EC key support requires algorithm
        // detection from the PKCS#8 structure.
        private const val RSA_ALGORITHM = "RSA"

        /** JSSE sentinel value for "unspecified port" in [SSLContext.createSSLEngine]. */
        private const val NO_PORT_HINT = -1

        private fun parsePemCertificates(pem: String): Array<Certificate> {
            val cf = CertificateFactory.getInstance(X509_CERT_TYPE)
            val certs = cf.generateCertificates(ByteArrayInputStream(pem.toByteArray()))
            return certs.toTypedArray()
        }

        private fun parsePemPrivateKey(pem: String): PrivateKey {
            val base64 = pem.lines()
                .filter { !it.startsWith("-----") }
                .joinToString("")
            val der = Base64.getDecoder().decode(base64)
            val kf = KeyFactory.getInstance(RSA_ALGORITHM)
            return kf.generatePrivate(PKCS8EncodedKeySpec(der))
        }
    }
}

/**
 * Trust manager that accepts all certificates (testing only).
 *
 * Disables all certificate validation. MUST NOT be used in production.
 */
private class InsecureTrustAllManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
    override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
}

/**
 * TrustManagerFactory wrapper for [InsecureTrustAllManager].
 *
 * Required because [SSLContext.init] accepts TrustManager[], not TrustManagerFactory.
 * This shim allows buildTrustManagerFactory to return a consistent type.
 */
private class InsecureTrustManagerFactory(
    private val tm: InsecureTrustAllManager,
) : TrustManagerFactory(
    object : TrustManagerFactorySpi() {
        override fun engineInit(ks: KeyStore?) {}
        override fun engineInit(spec: ManagerFactoryParameters?) {}
        override fun engineGetTrustManagers(): Array<TrustManager> = arrayOf(tm)
    },
    provider,
    TrustManagerFactory.getDefaultAlgorithm(),
) {
    companion object {
        private val provider = Security.getProviders().first()
    }
}
