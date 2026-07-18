package io.github.fukusaka.keel.client.http

/**
 * Identifies a connection target for pooling: connections are only
 * interchangeable when they go to the same host and port.
 *
 * The client is `http://` only for now, so the scheme is not part of the key
 * yet; it will be added when TLS lands (an `https` connection must not be
 * reused for an `http` request).
 */
internal data class RouteKey(val host: String, val port: Int)
