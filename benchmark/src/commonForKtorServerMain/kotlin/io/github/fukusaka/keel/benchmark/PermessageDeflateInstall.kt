package io.github.fukusaka.keel.benchmark

import io.ktor.server.websocket.WebSockets

/**
 * Install the RFC 7692 `permessage-deflate` extension on Ktor's
 * `WebSockets` plugin when the platform has it.
 *
 * Ktor's `io.ktor.websocket.WebSocketDeflateExtension` is JVM-only
 * because its implementation depends on `java.util.zip.Deflater`. On
 * Native targets the artefact does not include the class at all, so a
 * direct reference would not compile.
 *
 * The JVM actual installs the extension; the Native actual is a no-op.
 * On Native, wsbench's `permessage-deflate` offer goes un-negotiated and
 * the connection falls back to a plain echo — wsbench's
 * `-compression=true` and `-compression=false` legs converge to the
 * same throughput, which is the truthful reading of "Native ktor-keel
 * does not support deflate".
 */
internal expect fun WebSockets.WebSocketOptions.installPermessageDeflate()
