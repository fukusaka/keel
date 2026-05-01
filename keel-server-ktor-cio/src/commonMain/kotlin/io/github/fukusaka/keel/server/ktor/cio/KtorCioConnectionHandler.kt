package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.server.ktor.KeelApplicationEngine
import io.github.fukusaka.keel.server.ktor.KtorConnectionHandler
import kotlinx.coroutines.CoroutineScope

/**
 * [KtorConnectionHandler] backed by [ktor-http-cio's][io.ktor.http.cio]
 * `parseRequest` / `parseHttpBody` (Pattern C).
 *
 * Replaces keel's `:keel-codec-http` HTTP/1.1 codec with Ktor's own CIO parser
 * — the keel transport stack still drives the network I/O (kqueue / epoll /
 * io_uring / NIO / Netty / NWConnection / Node.js), but the parsing /
 * serialisation layer is supplied by `ktor-http-cio`.
 *
 * **Architecture**:
 * 1. Two coroutine pumps bridge keel's [PipelinedChannel] to a pair of Ktor
 *    [ByteChannel][io.ktor.utils.io.ByteChannel]s — one per direction.
 * 2. The inbound pump reads bytes from `channel.asBufferedSuspendSource()`
 *    and writes them to a Ktor `ByteChannel` that `parseRequest` reads from.
 * 3. The outbound pump reads bytes from a Ktor `ByteChannel` (written by the
 *    response writer) and forwards them to `channel.write` + `flush`.
 * 4. The keep-alive loop calls `parseRequest(input)` per request, builds a
 *    [KeelCioApplicationCall], dispatches via `engine.pipeline.execute(call)`,
 *    then drains the request body before reading the next request.
 *
 * **Status**: stub for Pattern C MVP scaffolding (PR introducing the
 * `:keel-server-ktor-cio` module).  The connection handler implementation —
 * byte-channel pumps, [io.ktor.http.cio.parseRequest] / [io.ktor.http.cio.parseHttpBody]
 * integration, and the [KeelCioApplicationCall] / Request / Response triple —
 * lands in a follow-up PR.
 */
internal class KtorCioConnectionHandler : KtorConnectionHandler {

    override suspend fun handle(
        channel: PipelinedChannel,
        scheme: String,
        engine: KeelApplicationEngine,
        scope: CoroutineScope,
    ) {
        runCatching { channel.close() }
        error(
            "KtorCioConnectionHandler is not yet implemented. The :keel-server-ktor-cio MVP " +
                "introduces the module skeleton + KeelCio factory; the byte-channel pumps " +
                "and ktor-http-cio request/response wiring will land in a follow-up PR.",
        )
    }
}
