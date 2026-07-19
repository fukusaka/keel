package io.github.fukusaka.keel.benchmark

import io.ktor.server.websocket.WebSockets

/**
 * Native actual — no-op. Ktor's `WebSocketDeflateExtension` uses
 * `java.util.zip.Deflater` and is JVM-only; the Native artefact of
 * `ktor-server-websockets` does not include the class.
 *
 * On Native the `/ws-deflate` route still exists — the handler is the
 * same plain echo as `/ws-echo` — but without the extension the server
 * never accepts the `permessage-deflate` offer in the handshake, so
 * wsbench's `-compression=true` and `-compression=false` legs converge
 * to the same plain throughput. That is the truthful reading of "Native
 * ktor-keel-* does not support deflate" rather than a connection
 * failure, which is what the pre-fix code surfaced as the `0 [FAILED]`
 * rows for `ktor-keel-epoll` / `ktor-keel-io-uring` in the 2026-06-19
 * WebSocket sweep.
 */
internal actual fun WebSockets.WebSocketOptions.installPermessageDeflate() {
    // No-op — see KDoc above.
}
