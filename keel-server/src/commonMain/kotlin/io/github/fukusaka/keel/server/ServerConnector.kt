package io.github.fukusaka.keel.server

/**
 * Server connector configuration describing a listening endpoint.
 *
 * Each connector represents a single (host, port) binding with optional
 * TLS. Multiple connectors enable HTTP + HTTPS on different ports.
 *
 * Lives in `:keel-server` so engine adapters (`:keel-server-ktor`) and
 * future HTTP-family servers (`:keel-server-http`) share the same shape
 * without either side owning the type.
 *
 * @param host Bind address (e.g. "0.0.0.0" for all interfaces).
 * @param port Port number. 0 lets the OS assign an ephemeral port.
 * @param tls TLS configuration. null = plain TCP (HTTP).
 */
data class ServerConnector(
    val host: String = "0.0.0.0",
    val port: Int = 0,
    val tls: TlsServerConfig? = null,
)
