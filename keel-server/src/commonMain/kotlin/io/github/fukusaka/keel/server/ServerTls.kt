package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.tls.TlsConfig

/**
 * Server-side TLS intent for a [ServerConnector] — engine-neutral and
 * unresolved.
 *
 * Holds the protocol-level [TlsConfig] (certificates, trust anchors,
 * verification mode, ALPN, SNI) plus a [ServerTlsStrategy] that says which
 * TLS mechanism to use. The concrete [TlsServerInstaller] / [io.github.fukusaka.keel.core.BindConfig]
 * is not chosen here — that resolution needs the engine and happens in
 * [ServerConnector.resolveBindConfig].
 *
 * [strategy] has no default: a [ServerConnector] is engine-agnostic, so
 * no strategy is correct for every engine (the POSIX engines have no
 * native TLS and require [ServerTlsStrategy.KeelCodec]). The caller must
 * pick one explicitly.
 *
 * @param config TLS settings shared by every connection on the connector.
 * @param strategy how to obtain the TLS implementation.
 */
public class ServerTls(
    public val config: TlsConfig,
    public val strategy: ServerTlsStrategy,
)
