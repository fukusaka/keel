package io.github.fukusaka.keel.benchmark

import io.ktor.server.websocket.WebSockets
import io.ktor.websocket.WebSocketDeflateExtension

/**
 * JVM actual — installs Ktor's `WebSocketDeflateExtension` so the
 * `/ws-deflate` route negotiates RFC 7692 `permessage-deflate` with
 * any client that offers the extension during the handshake. wsbench's
 * `-compression=true` path lights this branch up; `-compression=false`
 * skips negotiation and gets plain echo.
 *
 * The extension is JVM-only — its implementation calls into
 * `java.util.zip.Deflater` — which is why the [installPermessageDeflate]
 * carrier lives in the common Ktor source set as `expect` and the
 * matching no-op actual lives in `nativeMain`.
 */
internal actual fun WebSockets.WebSocketOptions.installPermessageDeflate() {
    extensions {
        install(WebSocketDeflateExtension)
    }
}
