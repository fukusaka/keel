package io.github.fukusaka.keel.ktor

import io.ktor.events.Events
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.engine.ApplicationEngineFactory

/**
 * [ApplicationEngineFactory] for the keel I/O engine.
 *
 * Usage:
 * ```
 * embeddedServer(Keel, port = 8080) {
 *     routing { get("/") { call.respondText("Hello") } }
 * }.start(wait = true)
 * ```
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
    ): KeelApplicationEngine {
        return KeelApplicationEngine(environment, monitor, developmentMode, configuration, applicationProvider)
    }
}
