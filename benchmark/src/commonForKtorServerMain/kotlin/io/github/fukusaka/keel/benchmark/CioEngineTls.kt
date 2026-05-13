package io.github.fukusaka.keel.benchmark

import io.ktor.server.engine.ApplicationEngine

/**
 * Platform-specific connector wiring for [CioEngine].
 *
 * Stock Ktor `embeddedServer(CIO, ...)` accepts both `connector` (HTTP)
 * and `sslConnector` (HTTPS) in the configuration block, but the
 * `sslConnector` factory is JVM-only in Ktor 3.4.1 (`io.ktor.server.engine`
 * `EngineConnectorConfigJvm.kt`); there is no `commonMain` overload yet.
 * Native targets therefore use a different actual that does not call
 * `sslConnector` and surfaces a clear error if `--tls=...` is requested.
 *
 * **No protective guard in benchmark code**: previously the benchmark
 * wrapped the connector setup with `require(config.tls == null) { ... }`
 * to short-circuit before reaching Ktor — this duplicated Ktor's own
 * runtime check (`CIOApplicationEngine.kt:224` throws
 * `UnsupportedOperationException("CIO Engine does not currently support
 * HTTPS")` for any `ConnectorType.HTTPS` connector). The benchmark
 * dropped the guard so Ktor's authoritative behaviour is what the
 * bench harness records — if Ktor ever adds CIO Server HTTPS support
 * (upstream issue ktorio/ktor#886, open since 2017), the bench picks
 * it up automatically without code change. The current observable
 * outcome is `STARTUP REFUSE` because Ktor itself throws on engine
 * start.
 */
internal expect fun ApplicationEngine.Configuration.cioConfigureConnector(
    config: BenchmarkConfig,
)
