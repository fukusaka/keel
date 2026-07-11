package io.github.fukusaka.keel.server.ktor

import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.CoroutineScope

/**
 * Codec-agnostic per-connection handler for the Ktor adapter.
 *
 * `:keel-server-ktor-base` keeps the engine-neutral plumbing (accept loop,
 * shutdown, configuration, Ktor `BaseApplicationEngine` integration) and
 * delegates per-connection HTTP handling to a [KtorConnectionHandler] supplied
 * by a sibling codec module:
 *
 * - `:keel-server-ktor` provides `KeelCodecConnectionHandler`, which installs
 *   `addHttp1ServerCodec()` from `:keel-codec-http` and bridges the parsed
 *   `HttpRequest` into a Ktor `ApplicationCall`.
 * - `:keel-server-ktor-cio` (future) provides `KtorCioConnectionHandler`,
 *   which uses `ktor-http-cio`'s `parseRequest` + Ktor `ByteReadChannel` /
 *   `ByteWriteChannel` over the channel directly.
 *
 * The handler owns the entire per-connection lifecycle: install the codec
 * (or set up streaming I/O), run the keep-alive read loop, build a Ktor
 * `ApplicationCall` per request, dispatch through `engine.pipeline.execute`,
 * and close the channel when the loop exits (peer close, error, or
 * `Connection: close` after the last response).
 *
 * **Threading**: the handler is invoked on the channel's `ioDispatcher`
 * (the engine EventLoop thread) — see `KeelApplicationEngine` accept loop.
 * Implementations are expected to honour [KeelApplicationEngine.Configuration.applicationDispatcher]
 * for the actual `pipeline.execute(call)` call to give applications a hook
 * for offloading blocking handlers off the EventLoop.
 *
 * **Test strategy**: no standalone contract test. This is a single-method
 * functional interface (the seam between the engine accept loop and a codec-
 * specific handler); it has no behaviour of its own to pin. Its concrete
 * implementations (`keel-server-ktor` / `keel-server-ktor-cio`) are exercised
 * by those modules' engine integration tests.
 */
public fun interface KtorConnectionHandler {
    /**
     * Handles a single accepted connection from accept until close.
     *
     * @param channel the accepted [PipelinedChannel] (the engine's accept
     *   loop has already invoked any TLS install hook from
     *   [io.github.fukusaka.keel.server.TlsServerConfig.initializeConnection]).
     * @param scheme `"http"` or `"https"`, propagated to Ktor's
     *   [io.ktor.http.RequestConnectionPoint].
     * @param engine the parent [KeelApplicationEngine], exposing
     *   `configuration` (keepAlive / applicationDispatcher), `pipeline`
     *   (Ktor's `EnginePipeline`), and `application` (the running
     *   [io.ktor.server.application.Application]).
     * @param scope the coroutine scope on which the handler runs (engine
     *   scope + channel `ioDispatcher`); used as the parent scope for
     *   `KeelApplicationCall`.
     */
    public suspend fun handle(
        channel: PipelinedChannel,
        scheme: String,
        engine: KeelApplicationEngine,
        scope: CoroutineScope,
    )
}
