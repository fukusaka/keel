package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.server.ktor.KeelApplicationEngine
import io.ktor.events.Events
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.engine.ApplicationEngineFactory

/**
 * [ApplicationEngineFactory] for the Ktor adapter using
 * [ktor-http-cio's][io.ktor.http.cio] HTTP parser instead of keel's own
 * codec stack (Pattern C).
 *
 * For Ktor users wanting keel's `:keel-codec-http` parser, use the
 * `Keel` factory from `:keel-server-ktor` (Pattern B).
 *
 * Usage:
 * ```
 * embeddedServer(KeelCio) {
 *     engine = NioEngine()
 *     connector { port = 8080 }
 * }.start(wait = true)
 * ```
 *
 * Wires [KeelApplicationEngine] (codec-agnostic skeleton from
 * `:keel-server-ktor-base`) with [KtorCioConnectionHandler] (per-connection
 * HTTP/1.1 handling using `ktor-http-cio`'s `parseRequest` / `parseHttpBody`).
 *
 * The factory exists so the keel transport stack (kqueue / epoll / io_uring /
 * NIO / Netty / NWConnection / Node.js) can be paired with Ktor's own HTTP
 * parser for benchmarking and feature-parity validation against the Pattern B
 * adapter.
 */
public object KeelCio : ApplicationEngineFactory<KeelApplicationEngine, KeelApplicationEngine.Configuration> {

    override fun configuration(
        configure: KeelApplicationEngine.Configuration.() -> Unit,
    ): KeelApplicationEngine.Configuration {
        return KeelApplicationEngine.Configuration().apply(configure)
    }

    override fun create(
        environment: ApplicationEnvironment,
        monitor: Events,
        developmentMode: Boolean,
        configuration: KeelApplicationEngine.Configuration,
        applicationProvider: () -> Application,
    ): KeelApplicationEngine = KeelApplicationEngine(
        environment = environment,
        monitor = monitor,
        developmentMode = developmentMode,
        configuration = configuration,
        applicationProvider = applicationProvider,
        connectionHandler = KtorCioConnectionHandler(),
    )
}
