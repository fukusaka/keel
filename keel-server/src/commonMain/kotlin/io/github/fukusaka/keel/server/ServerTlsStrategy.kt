package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.tls.TlsCodecFactory

/**
 * How a [ServerConnector] should obtain its server-side TLS implementation.
 *
 * TLS *intent* ([ServerTls.config]) is engine-neutral, but the *mechanism*
 * is not — some engines speak TLS natively (Netty `SslHandler`,
 * NWConnection, Node.js listener), others install keel's `TlsHandler` in
 * the pipeline. The strategy lets the caller declare which mechanism to
 * use without binding the connector to a concrete engine; resolution
 * happens in [ServerConnector.resolveBindConfig] once the engine is known.
 *
 * - [EngineNative] — use the engine's native TLS. Engines without it are
 *   rejected at resolution time.
 * - [KeelCodec] — use keel's `TlsHandler` with the given [TlsCodecFactory].
 *   Works on every engine, so it is the explicit "do not use engine-native
 *   TLS" choice.
 * - [Custom] — bring your own [TlsServerInstaller] (escape hatch).
 */
public sealed interface ServerTlsStrategy {

    /**
     * Use the engine's native server TLS (Netty `SslHandler`, NWConnection,
     * Node.js TLS listener). Engines that do not implement
     * [ServerTlsProvider] are rejected by [ServerConnector.resolveBindConfig].
     */
    public data object EngineNative : ServerTlsStrategy

    /**
     * Use keel's `TlsHandler` driven by [factory]. Engine-independent — it
     * works on every engine, so it is also the way to opt out of
     * engine-native TLS on engines that have it.
     *
     * @param factory codec factory used to create a fresh server codec per
     *   connection. The connector does not take ownership; the caller
     *   closes the factory.
     */
    public class KeelCodec(public val factory: TlsCodecFactory) : ServerTlsStrategy

    /**
     * Use a caller-supplied [TlsServerInstaller]. Escape hatch for
     * installers that are neither engine-native nor keel-codec based.
     *
     * @param installer the installer applied per connection.
     */
    public class Custom(public val installer: TlsServerInstaller) : ServerTlsStrategy
}
