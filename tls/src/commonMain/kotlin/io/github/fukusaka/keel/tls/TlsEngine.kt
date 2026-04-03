package io.github.fukusaka.keel.tls

/**
 * Factory for creating [TlsSession] instances.
 *
 * Each platform provides one or more implementations:
 * - Native: `MbedTlsEngine`, `OpenSslEngine` (via cinterop)
 * - JVM: `JsseTlsEngine` (JDK standard, optionally Conscrypt)
 * - macOS: `NwTlsEngine` (delegates to NWConnection's built-in TLS)
 * - JS: `NodeTlsEngine` (Node.js `tls` module)
 *
 * The engine is stateless with respect to individual connections.
 * It may cache platform-specific config objects (e.g. `SSL_CTX`, `SSLContext`)
 * internally for reuse across sessions created with the same [TlsConfig].
 *
 * **Usage**:
 * ```
 * val engine: TlsEngine = MbedTlsEngine()  // or platform-specific
 * val session = engine.createServerSession(config, fd)
 * session.handshake()
 * session.read(buf) / session.write(buf)
 * session.close()
 * ```
 */
interface TlsEngine {

    /**
     * Creates a server-side TLS session.
     *
     * The returned [TlsSession] is ready for [TlsSession.handshake].
     * The caller is responsible for the underlying transport (fd, channel, etc.)
     * and must close it after closing the TLS session.
     *
     * @param config TLS configuration (certificates, verification, ALPN).
     * @return A new [TlsSession] in pre-handshake state.
     */
    fun createServerSession(config: TlsConfig): TlsSession

    /**
     * Creates a client-side TLS session.
     *
     * @param config TLS configuration (trust anchors, SNI, ALPN).
     * @return A new [TlsSession] in pre-handshake state.
     */
    fun createClientSession(config: TlsConfig): TlsSession
}
