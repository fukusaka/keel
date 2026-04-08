@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.tls.awslc

import awslc.OPENSSL_init_ssl
import awslc.SSL
import awslc.SSL_CTX
import awslc.SSL_CTX_free
import awslc.SSL_CTX_new
import awslc.SSL_CTX_set_default_verify_paths
import awslc.SSL_CTX_set_verify
import awslc.SSL_VERIFY_NONE
import awslc.SSL_VERIFY_PEER
import awslc.SSL_new
import awslc.SSL_set_accept_state
import awslc.SSL_set_connect_state
import awslc.TLS_method
import awslc.keel_awslc_bio_ctx
import awslc.keel_awslc_bio_setup
import awslc.keel_awslc_ctx_load_ca_pem
import awslc.keel_awslc_ctx_load_pem_cert
import awslc.keel_awslc_ctx_load_pem_key
import awslc.keel_awslc_err_string
import awslc.keel_awslc_set_sni
import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.asPem
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsErrorCategory
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString

/**
 * [TlsCodecFactory] implementation backed by AWS-LC (BoringSSL fork).
 *
 * Creates [AwsLcCodec] instances with per-connection [SSL] objects
 * and pointer-based BIO transport. API-compatible with OpenSSL 3.x;
 * same architecture as [OpenSslCodecFactory].
 *
 * **Supported certificate sources**: [TlsCertificateSource.Pem].
 * DER, KeyStoreFile, and SystemKeychain are not supported on this backend.
 */
class AwsLcCodecFactory : TlsCodecFactory {

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
        SSL_CTX_free(ctx)

        return AwsLcCodec(ssl, bioCtx)
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

        return AwsLcCodec(ssl, bioCtx)
    }

    override fun close() {
        // SSL_CTX is freed per-codec. Nothing to release here.
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

        return ctx
    }

    private fun loadCertificates(ctx: CPointer<SSL_CTX>, config: TlsConfig) {
        val certSource = config.certificates ?: return

        when (certSource) {
            is TlsCertificateSource.Pem, is TlsCertificateSource.Der -> {
                val pem = certSource.asPem()
                val certPem = pem.certificatePem
                val certRet = keel_awslc_ctx_load_pem_cert(ctx, certPem, certPem.length)
                if (certRet != 1) {
                    SSL_CTX_free(ctx)
                    throw TlsException(
                        "Failed to load PEM certificate: ${errorString()}",
                        TlsErrorCategory.HANDSHAKE_FAILED,
                    )
                }

                val keyPem = pem.privateKeyPem
                val keyRet = keel_awslc_ctx_load_pem_key(ctx, keyPem, keyPem.length)
                if (keyRet != 1) {
                    SSL_CTX_free(ctx)
                    throw TlsException(
                        "Failed to load PEM private key: ${errorString()}",
                        TlsErrorCategory.HANDSHAKE_FAILED,
                    )
                }
            }
            is TlsCertificateSource.KeyStoreFile ->
                error("KeyStoreFile certificate source is not supported by AWS-LC backend")
            is TlsCertificateSource.SystemKeychain ->
                error("SystemKeychain is not supported by AWS-LC backend")
        }
    }

    private fun configureTrust(ctx: CPointer<SSL_CTX>, config: TlsConfig) {
        when (val trustSource = config.trustAnchors) {
            null, is TlsTrustSource.SystemDefault -> {
                SSL_CTX_set_default_verify_paths(ctx)
            }
            is TlsTrustSource.Pem -> {
                val caPem = trustSource.caPem
                val count = keel_awslc_ctx_load_ca_pem(ctx, caPem, caPem.length)
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
            TlsVerifyMode.PEER, TlsVerifyMode.REQUIRED ->
                SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, null)
        }
    }

    private fun configureSni(ssl: CPointer<SSL>, config: TlsConfig) {
        val name = config.serverName ?: return
        keel_awslc_set_sni(ssl, name)
    }

    private fun setupBio(ssl: CPointer<SSL>): keel_awslc_bio_ctx {
        val bioCtx = nativeHeap.alloc<keel_awslc_bio_ctx>()
        val ret = keel_awslc_bio_setup(ssl, bioCtx.ptr)
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
            keel_awslc_err_string()?.toKString() ?: "unknown error"
    }
}
