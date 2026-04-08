package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.pipeline.PipelinedChannel

/**
 * Factory for creating [TlsCodec] instances.
 *
 * Each platform provides one or more implementations:
 * - Native: `MbedTlsCodecFactory` (via cinterop)
 * - JVM: `JsseTlsCodecFactory` (JDK standard SSLContext)
 * - macOS: future OS-native factory
 * - JS: `NodeTlsCodecFactory` (Node.js `tls` module)
 *
 * Implements [TlsInstaller] with a default [install] that creates a server
 * codec and adds [TlsHandler] at the pipeline HEAD. Engine-specific installers
 * (e.g., `NettySslInstaller`) override this to install TLS at the transport
 * level.
 *
 * Implementations may cache platform-specific config objects (e.g., `SSL_CTX`,
 * `SSLContext`) internally for reuse across codecs created with the same
 * [TlsConfig]. Call [close] to release these shared resources.
 *
 * **Thread safety**: factory creation and [close] may be called from any thread.
 * Individual [TlsCodec] instances are EventLoop-thread-confined.
 */
interface TlsCodecFactory : TlsInstaller, AutoCloseable {

    /**
     * Creates a server-side TLS codec.
     *
     * @param config TLS configuration (certificates, verification, ALPN).
     * @return A new [TlsCodec] ready for [TlsCodec.unprotect] (handshake).
     */
    fun createServerCodec(config: TlsConfig): TlsCodec

    /**
     * Creates a client-side TLS codec.
     *
     * @param config TLS configuration (trust anchors, SNI, ALPN).
     * @return A new [TlsCodec] ready for [TlsCodec.protect] (ClientHello).
     */
    fun createClientCodec(config: TlsConfig): TlsCodec

    /**
     * Installs keel [TlsHandler] at the pipeline HEAD.
     *
     * Creates a server codec via [createServerCodec] and adds it as the
     * first handler in the pipeline, ensuring TLS protection sits closest
     * to the transport layer.
     */
    override fun install(channel: PipelinedChannel, config: TlsConfig) {
        val codec = createServerCodec(config)
        channel.pipeline.addFirst("tls", TlsHandler(codec))
    }

    /** Releases shared resources (e.g., `SSL_CTX`, `SSLContext`). */
    override fun close()
}
