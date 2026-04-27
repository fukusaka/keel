package io.github.fukusaka.keel.server.ktor

import io.ktor.events.Events
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.engine.ApplicationEngineFactory

/**
 * [ApplicationEngineFactory] for the Ktor adapter using keel's
 * [HttpRequestDecoder][io.github.fukusaka.keel.codec.http.HttpRequestDecoder]
 * / [HttpResponseEncoder][io.github.fukusaka.keel.codec.http.HttpResponseEncoder]
 * codec stack from `:keel-codec-http` (Pattern B).
 *
 * For Ktor users wanting Ktor's own `ktor-http-cio` HTTP parser instead,
 * use the `KeelCio` factory from `:keel-server-ktor-cio` (Pattern C).
 *
 * Usage:
 * ```
 * embeddedServer(Keel) {
 *     engine = NioEngine()
 *     connector { port = 8080 }
 * }.start(wait = true)
 * ```
 *
 * Wires [KeelApplicationEngine] (codec-agnostic skeleton from
 * `:keel-server-ktor-base`) with [KeelCodecConnectionHandler]
 * (per-connection HTTP/1.1 handling using keel codec).
 */
public object Keel : ApplicationEngineFactory<KeelApplicationEngine, KeelApplicationEngine.Configuration> {

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
        connectionHandler = KeelCodecConnectionHandler(),
    )
}
