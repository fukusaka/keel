@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.tls.openssl

import io.github.fukusaka.keel.tls.TlsCertificateSource
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsErrorCategory
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsTrustSource
import io.github.fukusaka.keel.tls.TlsVerifyMode
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import openssl.BIO
import openssl.OPENSSL_init_ssl
import openssl.SSL
import openssl.SSL_CTX
import openssl.SSL_CTX_free
import openssl.SSL_CTX_new
import openssl.SSL_CTX_set_default_verify_paths
import openssl.SSL_CTX_set_verify
import openssl.SSL_VERIFY_NONE
import openssl.SSL_VERIFY_PEER
import openssl.SSL_free
import openssl.SSL_new
import openssl.SSL_set_accept_state
import openssl.SSL_set_connect_state
import openssl.TLS_method
import openssl.keel_openssl_bio_setup
import openssl.keel_openssl_ctx_load_ca_pem
import openssl.keel_openssl_ctx_load_pem_cert
import openssl.keel_openssl_ctx_load_pem_key
import openssl.keel_openssl_err_string
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

        val (readBio, writeBio) = setupBio(ssl, ctx)

        // SSL_CTX can be freed after SSL_new — SSL holds a reference.
        SSL_CTX_free(ctx)

        return OpenSslCodec(ssl, readBio, writeBio)
    }

    override fun createClientCodec(config: TlsConfig): TlsCodec {
        val ctx = buildSslCtx(config)
        val ssl = SSL_new(ctx) ?: throw TlsException(
            "SSL_new failed: ${errorString()}",
            TlsErrorCategory.HANDSHAKE_FAILED,
        )
        SSL_set_connect_state(ssl)

        configureSni(ssl, config)

        val (readBio, writeBio) = setupBio(ssl, ctx)
        SSL_CTX_free(ctx)

        return OpenSslCodec(ssl, readBio, writeBio)
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

        return ctx
    }

    private fun loadCertificates(ctx: CPointer<SSL_CTX>, config: TlsConfig) {
        val certSource = config.certificates ?: return

        when (certSource) {
            is TlsCertificateSource.Pem -> {
                val certPem = certSource.certificatePem
                val certRet = keel_openssl_ctx_load_pem_cert(ctx, certPem, certPem.length)
                if (certRet != 1) {
                    SSL_CTX_free(ctx)
                    throw TlsException(
                        "Failed to load PEM certificate: ${errorString()}",
                        TlsErrorCategory.HANDSHAKE_FAILED,
                    )
                }

                val keyPem = certSource.privateKeyPem
                val keyRet = keel_openssl_ctx_load_pem_key(ctx, keyPem, keyPem.length)
                if (keyRet != 1) {
                    SSL_CTX_free(ctx)
                    throw TlsException(
                        "Failed to load PEM private key: ${errorString()}",
                        TlsErrorCategory.HANDSHAKE_FAILED,
                    )
                }
            }
            is TlsCertificateSource.Der ->
                error("DER certificate source is not supported by OpenSSL backend")
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
            TlsVerifyMode.PEER, TlsVerifyMode.REQUIRED ->
                SSL_CTX_set_verify(ctx, SSL_VERIFY_PEER, null)
        }
    }

    private fun configureSni(ssl: CPointer<SSL>, config: TlsConfig) {
        val name = config.serverName ?: return
        keel_openssl_set_sni(ssl, name)
    }

    private fun setupBio(
        ssl: CPointer<SSL>,
        ctx: CPointer<SSL_CTX>,
    ): Pair<CPointer<BIO>, CPointer<BIO>> = memScoped {
        val rbioVar = alloc<CPointerVar<BIO>>()
        val wbioVar = alloc<CPointerVar<BIO>>()
        val ret = keel_openssl_bio_setup(ssl, rbioVar.ptr, wbioVar.ptr)
        if (ret != 0) {
            SSL_free(ssl)
            SSL_CTX_free(ctx)
            throw TlsException(
                "Failed to setup memory BIO: ${errorString()}",
                TlsErrorCategory.HANDSHAKE_FAILED,
            )
        }
        val readBio = rbioVar.value ?: throw TlsException(
            "BIO setup returned null readBio",
            TlsErrorCategory.HANDSHAKE_FAILED,
        )
        val writeBio = wbioVar.value ?: throw TlsException(
            "BIO setup returned null writeBio",
            TlsErrorCategory.HANDSHAKE_FAILED,
        )
        readBio to writeBio
    }

    companion object {
        private fun errorString(): String =
            keel_openssl_err_string()?.toKString() ?: "unknown error"
    }
}
