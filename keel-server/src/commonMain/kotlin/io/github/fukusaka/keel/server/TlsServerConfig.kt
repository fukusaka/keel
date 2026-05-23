package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.SocketOptions
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.tls.TlsConfig
import io.github.fukusaka.keel.tls.TlsHandler

/**
 * Per-listener TLS server configuration.
 *
 * Extends [BindConfig] so it can be passed directly to
 * [io.github.fukusaka.keel.core.StreamEngine.bindPipeline] /
 * [io.github.fukusaka.keel.core.StreamEngine.bind] as the config
 * parameter. Holds a [TlsConfig] (certificates, trust anchors, ALPN,
 * etc.) and an optional [TlsServerInstaller] for per-connection TLS
 * setup.
 *
 * - **[installer] = non-null**: per-connection TLS. [initializeConnection]
 *   calls `installer.install()` on every accepted channel. Use
 *   [TlsCodecServerInstaller] to install keel's `TlsHandler`;
 *   engine-specific installers (e.g., Netty's `SslHandler` adapter)
 *   install at the transport level.
 * - **[installer] = null**: engine-native listener-level TLS (Node.js,
 *   NWConnection). The engine configures TLS at listener creation time
 *   from the [TlsConfig] alone. Per-connection-only engines (kqueue,
 *   epoll, NIO) reject this combination.
 *
 * Lives in `:keel-server` (not `:keel-tls`) because the type mixes TLS
 * protocol settings with server-binding plumbing (`BindConfig` ancestry,
 * pipeline channel install hook). `:keel-tls` stays focused on protocol
 * primitives.
 *
 * @param tls TLS settings (certificates, trust, verify mode, ALPN, SNI).
 * @param installer per-connection TLS installer, or null for engine-native TLS.
 * @param backlog TCP listen backlog (inherited from [BindConfig]).
 * @param childSocketOptions socket options applied to every accepted
 *   client fd (inherited from [BindConfig]).
 * @param readBufferSize per-server read buffer size override (inherited
 *   from [BindConfig]).
 * @param plaintextBufferSize per-server override of the TLS plaintext
 *   buffer size — the buffer the downstream codec sees as its "recv
 *   segment" on a TLS connection. `null` (default) keeps
 *   [TlsHandler.TLS_PLAINTEXT_BUF_SIZE_DEFAULT][io.github.fukusaka.keel.tls.TlsHandler.Companion.TLS_PLAINTEXT_BUF_SIZE_DEFAULT]
 *   (16 KiB). When non-null, validated via
 *   [TlsHandler.requireValidPlaintextBufferSize][io.github.fukusaka.keel.tls.TlsHandler.Companion.requireValidPlaintextBufferSize]
 *   (power of two in 16 KiB..1 MiB) and forwarded to the
 *   [TlsServerInstaller] three-argument overload. Honoured by
 *   installers that wrap `TlsHandler` (such as [TlsCodecServerInstaller]);
 *   engine-native installers (e.g., Netty's `SslHandler` adapter) manage
 *   their own buffer sizing and ignore the override.
 */
public class TlsServerConfig(
    public val tls: TlsConfig,
    public val installer: TlsServerInstaller? = null,
    backlog: Int = DEFAULT_BACKLOG,
    childSocketOptions: SocketOptions = SocketOptions.DEFAULT,
    readBufferSize: Int? = null,
    public val plaintextBufferSize: Int? = null,
) : BindConfig(backlog, childSocketOptions, readBufferSize) {

    init {
        plaintextBufferSize?.let { TlsHandler.requireValidPlaintextBufferSize(it) }
    }

    /**
     * Installs TLS on the channel via [installer], forwarding the
     * [plaintextBufferSize] override (or the default when null).
     *
     * No-op when [installer] is null (engine-native listener-level TLS
     * handles TLS at the listener level, so per-connection initialisation
     * is not needed).
     */
    override fun initializeConnection(channel: PipelinedChannel) {
        installer?.install(channel, tls, plaintextBufferSize ?: TlsHandler.TLS_PLAINTEXT_BUF_SIZE_DEFAULT)
    }
}
