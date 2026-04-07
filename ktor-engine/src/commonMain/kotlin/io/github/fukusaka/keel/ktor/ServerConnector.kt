package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.tls.TlsConnectorConfig

/**
 * Server connector configuration describing a listening endpoint.
 *
 * Each connector represents a single (host, port) binding with optional
 * TLS. Multiple connectors enable HTTP + HTTPS on different ports.
 *
 * Currently placed in `:ktor-engine` as a temporary location. Will be
 * moved to a shared module (`:server-core` or similar) when the
 * `:server` module is created.
 *
 * @param host Bind address (e.g. "0.0.0.0" for all interfaces).
 * @param port Port number. 0 lets the OS assign an ephemeral port.
 * @param tls TLS configuration. null = plain TCP (HTTP).
 */
data class ServerConnector(
    val host: String = "0.0.0.0",
    val port: Int = 0,
    val tls: TlsConnectorConfig? = null,
)
