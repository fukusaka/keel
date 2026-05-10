package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.server.ktor.KeelApplicationEngine
import io.ktor.events.Events
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.engine.ApplicationEngineFactory

/**
 * [ApplicationEngineFactory] for the Ktor adapter using
 * [ktor-http-cio's][io.ktor.http.cio] HTTP parser instead of keel's own
 * codec stack.
 *
 * For Ktor users wanting keel's `:keel-codec-http` parser, use the
 * `Keel` factory from `:keel-server-ktor`.
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
 * parser for benchmarking and feature-parity validation against the
 * `:keel-server-ktor` adapter.
 *
 * **Kotlin/Native parser serialisation**: ktor-io's `DefaultPool.posix.kt`
 * runs the subclass `clearInstance` hook inside `synchronized(lock)`, and
 * ktor-http-cio's `HeadersDataPool` exploits that anti-pattern by reaching
 * into another pool's recycle inside its own clearInstance. On
 * Kotlin/Native the `SynchronizedObject` lock escalates to `pthread_mutex`
 * on contention; under multi-worker accept bursts the cascading
 * nested-lock wait collapses parser throughput to ≈ 0 RPS. This adapter
 * therefore serialises **both** `parseRequest` (header borrow) **and**
 * `request.release()` (header recycle) through a process-wide mutex on
 * Native targets — see [HeaderParseMutex] for evidence and trade-offs.
 * The JVM is unaffected (`synchronized` is reentrant + biased-locking +
 * JIT-optimised) and runs both ends concurrently as before.
 *
 * The serialisation caps Native single-host parser throughput at the
 * single-core parse rate (≈ 43 k RPS for `/hello` on macOS M1, since
 * `parseHttpBody` and I/O still parallelise).  For higher throughput on
 * Native, prefer the keel-native HTTP codec via the `pipeline-http-*`
 * engines (`addHttp1ServerCodec`) which parses on the I/O thread
 * without ktor's pool lock and reaches > 150 k RPS.
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
