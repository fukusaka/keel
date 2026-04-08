package io.github.fukusaka.keel.engine.nodejs

/**
 * Node.js `tls` module bindings for TLS server support.
 *
 * `tls.Server` extends `net.Server`, so the returned [Server] interface
 * is compatible with all existing server lifecycle management.
 * `tls.TLSSocket` extends `net.Socket`, so accepted connections work
 * with [NodePipelinedChannel] without modification.
 *
 * The `secureConnection` event (instead of `connection`) fires after
 * the TLS handshake completes, providing a ready-to-use encrypted socket.
 */
@JsModule("tls")
@JsNonModule
external object Tls {
    /**
     * Creates a TLS server.
     *
     * @param options TLS options (dynamic object with `key`, `cert`, etc.)
     * @param secureConnectionListener Callback for each accepted TLS connection.
     *        The socket parameter is a `tls.TLSSocket` (compatible with [Socket]).
     */
    fun createServer(options: dynamic, secureConnectionListener: (socket: Socket) -> Unit): Server
}
