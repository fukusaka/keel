package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecFactory
import io.github.fukusaka.keel.tls.TlsConfig

/**
 * [TlsCodecFactory] implementation for Mbed TLS 4.x.
 *
 * Creates [MbedTlsCodec] instances for server and client TLS.
 * Currently stateless — each codec manages its own ssl_config/ssl_context.
 * Future optimization: cache `mbedtls_ssl_config` per TlsConfig for reuse.
 */
class MbedTlsCodecFactory : TlsCodecFactory {

    override fun createServerCodec(config: TlsConfig): TlsCodec =
        MbedTlsCodec(isServer = true, config = config)

    override fun createClientCodec(config: TlsConfig): TlsCodec =
        MbedTlsCodec(isServer = false, config = config)

    override fun close() {
        // Currently stateless — no shared resources to release.
    }
}
