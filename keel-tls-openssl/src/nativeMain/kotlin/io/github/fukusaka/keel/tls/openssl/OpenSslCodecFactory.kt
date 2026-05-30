@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.tls.openssl

import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.asPem
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsErrorCategory
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import io.github.fukusaka.keel.tls.TlsVersion
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import openssl.OPENSSL_init_ssl
import openssl.keel_openssl_bio_ctx
import openssl.SSL
import openssl.SSL_CTX
import openssl.SSL_CTX_free
import openssl.SSL_CTX_new
import openssl.SSL_CTX_set_default_verify_paths
import openssl.SSL_CTX_set_verify
import openssl.SSL_VERIFY_FAIL_IF_NO_PEER_CERT
import openssl.SSL_VERIFY_NONE
import openssl.SSL_VERIFY_PEER
import openssl.SSL_new
import openssl.SSL_set_accept_state
import openssl.SSL_set_connect_state
import openssl.TLS1_2_VERSION
import openssl.TLS1_3_VERSION
import openssl.TLS_method
import openssl.keel_openssl_bio_setup
import openssl.keel_openssl_ctx_load_ca_pem
import openssl.keel_openssl_ctx_load_pem_cert
import openssl.keel_openssl_ctx_load_pem_key
import openssl.keel_openssl_err_string
import openssl.keel_openssl_set_max_proto_version
import openssl.keel_openssl_set_min_proto_version
import openssl.keel_openssl_set_sni

/**
 * [TlsCodecFactory] implementation backed by OpenSSL 3.x [SSL_CTX].
 *
 * Creates [OpenSslCodec] instances with per-connection [SSL] objects
 * and memory BIO transport. A new [SSL_CTX] is built for each
 * [TlsConfig] (future optimization: cache SSL_CTX per config).
 *
 * **Supported certificate sources**: [TlsCertificateSource.Pem].
 * DER, KeyStoreFile, and SystemKeychain are not supported on this backend.
 */
class OpenSslCodecFactory : TlsCodecFactory {

    init {
        OPENSSL_init_ssl(0u, null)
    }

    override fun createServerCodec(config: TlsConfig): TlsCodec {
        val ctx = buildSslCtx(config)
        val ssl = SSL_new(ctx) ?: throw TlsException(
            "SSL_new failed: ${errorString()}",
            TlsErrorCategory.HANDSHAKE_FAILED,
        )
        SSL_set_accept_state(ssl)

        val bioCtx = setupBio(ssl)

        // SSL_CTX can be freed after SSL_new — SSL holds a reference.
        SSL_CTX_free(ctx)

        return OpenSslCodec(ssl, bioCtx)
    }

    override fun createClientCodec(config: TlsConfig): TlsCodec {
        val ctx = buildSslCtx(config)
        val ssl = SSL_new(ctx) ?: throw TlsException(
            "SSL_new failed: ${errorString()}",
            TlsErrorCategory.HANDSHAKE_FAILED,
        )
        SSL_set_connect_state(ssl)

        configureSni(ssl, config)

        val bioCtx = setupBio(ssl)
        SSL_CTX_free(ctx)

        return OpenSslCodec(ssl, bioCtx)
    }

    override fun close() {
        // SSL_CTX is freed per-codec in createServerCodec/createClientCodec.
        // Nothing to release here.
    }

    private fun buildSslCtx(config: TlsConfig): CPointer<SSL_CTX> {
        val method = TLS_method()
        val ctx = SSL_CTX_new(method) ?: throw TlsException(
            "SSL_CTX_new failed: ${errorString()}",
            TlsErrorCategory.HANDSHAKE_FAILED,
        )

        loadCertificates(ctx, config)
        configureTrust(ctx, config)
        configureVerification(ctx, config)
        configureProtocols(ctx, config)

        return ctx
    }

    /**
     * Pins the negotiable protocol version range via
     * `SSL_CTX_set_min_proto_version` / `set_max_proto_version` (through
     * the `keel_openssl_*` wrappers — the originals are macros). Both
     * bounds are set explicitly: the floor ([TlsConfig.minVersion],
     * default TLS 1.2) so the system `openssl.cnf` `MinProtocol` cannot
     * lower it, and the ceiling ([TlsConfig.maxVersion] or [TlsVersion.TLS1_3]
     * when null) so keel never negotiates a version it does not enumerate.
     */
    private fun configureProtocols(ctx: CPointer<SSL_CTX>, config: TlsConfig) {
        if (keel_openssl_set_min_proto_version(ctx, opensslVersion(config.minVersion)) != 1) {
            SSL_CTX_free(ctx)
            throw TlsException("Failed to set min proto version: ${errorString()}", TlsErrorCategory.HANDSHAKE_FAILED)
        }
        val max = config.maxVersion ?: TlsVersion.TLS1_3
        if (keel_openssl_set_max_proto_version(ctx, opensslVersion(max)) != 1) {
            SSL_CTX_free(ctx)
            throw TlsException("Failed to set max proto version: ${errorString()}", TlsErrorCategory.HANDSHAKE_FAILED)
        }
    }

    private fun opensslVersion(version: TlsVersion): Int = when (version) {
        TlsVersion.TLS1_2 -> TLS1_2_VERSION
        TlsVersion.TLS1_3 -> TLS1_3_VERSION
    }

    private fun loadCertificates(ctx: CPointer<SSL_CTX>, config: TlsConfig) {
        val certSource = config.certificates ?: return

        when (certSource) {
            is TlsCertificateSource.Pem, is TlsCertificateSource.Der -> {
                val pem = certSource.asPem()
                val certPem = pem.certificatePem
                val certRet = keel_openssl_ctx_load_pem_cert(ctx, certPem, certPem.length)
                if (certRet != 1) {
                    SSL_CTX_free(ctx)
                    throw TlsException(
                        "Failed to load PEM certificate: ${errorString()}",
                        TlsErrorCategory.HANDSHAKE_FAILED,
                    )
                }

                val keyPem = pem.privateKeyPem
                val keyRet = keel_openssl_ctx_load_pem_key(ctx, keyPem, keyPem.length)
                if (keyRet != 1) {
                    SSL_CTX_free(ctx)
                    throw TlsException(
                        "Failed to load PEM private key: ${errorString()}",
                        TlsErrorCategory.HANDSHAKE_FAILED,
                    )
                }
            }
            is TlsCertificateSource.KeyStoreFile ->
                error("KeyStoreFile certificate source is not supported by OpenSSL backend")
            is TlsCertificateSource.SystemKeychain ->
                error("SystemKeychain is not supported by OpenSSL backend")
        }
    }

    private fun configureTrust(ctx: CPointer<SSL_CTX>, config: TlsConfig) {
        val trustSource = config.trustAnchors

        when (trustSource) {
            null, is TlsTrustSource.SystemDefault -> {
                SSL_CTX_set_default_verify_paths(ctx)
            }
            is TlsTrustSource.Pem -> {
                val caPem = trustSource.caPem
                val count = keel_openssl_ctx_load_ca_pem(ctx, caPem, caPem.length)
                if (count == 0) {
                    SSL_CTX_free(ctx)
                    throw TlsException(
                        "Failed to load CA PEM: ${errorString()}",
                        TlsErrorCategory.HANDSHAKE_FAILED,
                    )
                }
            }
            is TlsTrustSource.InsecureTrustAll -> {
                SSL_CTX_set_verify(ctx, SSL_VERIFY_NONE, null)
            }
        }
    }

    private fun configureVerification(ctx: CPointer<SSL_CTX>, config: TlsConfig) {
        when (config.verifyMode) {
            TlsVerifyMode.NONE ->
                SSL_CTX_set_verify(ctx, SSL_VERIFY_NONE, null)
            TlsVerifyMode.PEER ->
                SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, null)
            // REQUIRED adds FAIL_IF_NO_PEER_CERT so a server aborts the
            // handshake when the peer presents no certificate (mutual TLS);
            // PEER would silently accept a missing cert. (Server-only flag —
            // OpenSSL ignores it on a client, where the server always sends
            // a cert.)
            TlsVerifyMode.REQUIRED ->
                SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER or SSL_VERIFY_FAIL_IF_NO_PEER_CERT, null)
        }
    }

    private fun configureSni(ssl: CPointer<SSL>, config: TlsConfig) {
        val name = config.serverName ?: return
        keel_openssl_set_sni(ssl, name)
    }

    private fun setupBio(ssl: CPointer<SSL>): keel_openssl_bio_ctx {
        val bioCtx = nativeHeap.alloc<keel_openssl_bio_ctx>()
        val ret = keel_openssl_bio_setup(ssl, bioCtx.ptr)
        if (ret != 0) {
            nativeHeap.free(bioCtx.rawPtr)
            throw TlsException(
                "Failed to setup pointer-based BIO: ${errorString()}",
                TlsErrorCategory.HANDSHAKE_FAILED,
            )
        }
        return bioCtx
    }

    companion object {
        private fun errorString(): String =
            keel_openssl_err_string()?.toKString() ?: "unknown error"
    }
}
