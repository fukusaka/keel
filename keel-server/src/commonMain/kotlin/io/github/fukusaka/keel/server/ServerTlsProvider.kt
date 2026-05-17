package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.tls.TlsConfig

/**
 * Optional capability interface implemented by engines that provide
 * native server-side TLS.
 *
 * Engines whose underlying runtime speaks TLS itself — Netty (`SslHandler`),
 * NWConnection (`sec_protocol_options`), Node.js (`tls.createServer`) —
 * implement this so a [ServerConnector] using [ServerTlsStrategy.EngineNative]
 * can obtain an engine-specific [BindConfig]. POSIX engines (kqueue, epoll,
 * NIO, io_uring) do not implement it; for those, callers must use
 * [ServerTlsStrategy.KeelCodec], which works on every engine.
 *
 * [ServerConnector.resolveBindConfig] casts the engine to this interface
 * via `as?` and reports an actionable error when the cast fails.
 */
public interface ServerTlsProvider {

    /**
     * Builds an engine-specific [BindConfig] that installs native TLS for
     * every accepted connection.
     *
     * @param tls TLS settings (certificates, trust, verify mode, ALPN, SNI).
     * @param backlog TCP listen backlog for the resulting bind config.
     * @param socketOptions socket options applied to every accepted client fd.
     * @return a [BindConfig] (typically a [TlsServerConfig] or an
     *   engine-specific subclass) ready to pass to
     *   [io.github.fukusaka.keel.core.StreamEngine.bindPipeline].
     */
    public fun nativeTlsBindConfig(
        tls: TlsConfig,
        backlog: Int,
        socketOptions: SocketOptions,
    ): BindConfig
}
