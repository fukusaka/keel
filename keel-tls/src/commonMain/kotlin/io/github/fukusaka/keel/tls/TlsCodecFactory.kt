package io.github.fukusaka.keel.tls

/**
 * Factory for creating [TlsCodec] instances.
 *
 * Each platform provides one or more implementations:
 * - Native: `MbedTlsCodecFactory` (via cinterop)
 * - JVM: `JsseTlsCodecFactory` (JDK standard SSLContext)
 * - macOS: future OS-native factory
 * - JS: `NodeTlsCodecFactory` (Node.js `tls` module)
 *
 * Implementations may cache platform-specific config objects (e.g., `SSL_CTX`,
 * `SSLContext`) internally for reuse across codecs created with the same
 * [TlsConfig]. Call [close] to release these shared resources.
 *
 * **Thread safety**: factory creation and [close] may be called from any thread.
 * Individual [TlsCodec] instances are EventLoop-thread-confined.
 */
interface TlsCodecFactory : AutoCloseable {

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

    /** Releases shared resources (e.g., `SSL_CTX`, `SSLContext`). */
    override fun close()
}
