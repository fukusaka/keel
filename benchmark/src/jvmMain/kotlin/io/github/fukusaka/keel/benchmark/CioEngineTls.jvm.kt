package io.github.fukusaka.keel.benchmark

import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.connector
import io.ktor.server.engine.sslConnector

/**
 * JVM actual for [cioConfigureConnector].
 *
 * Calls Ktor's official `sslConnector(keyStore, keyAlias, ...)`
 * (`io.ktor.server.engine.EngineConnectorConfigJvm.kt`) when TLS is
 * requested. The same JKS fixture as [KtorNettyEngine] /
 * [SpringEngine] (`buildBenchmarkKeyStore` + `BENCHMARK_KEY_ALIAS` +
 * `BENCHMARK_KEY_PASSWORD`) is used so the JSSE comparison is
 * apples-to-apples against the other JSSE-backed reference engines.
 *
 * Ktor 3.4.1 CIO Server itself does not yet implement HTTPS
 * (`CIOApplicationEngine.kt:224` throws `UnsupportedOperationException`
 * for any `ConnectorType.HTTPS` connector — see ktorio/ktor#886 OPEN
 * since 2017). When TLS is requested, the bench therefore observes a
 * Ktor-level `UnsupportedOperationException` shortly after
 * "Application started in N ms" rather than a `require()` failure at
 * the keel benchmark layer. The harness records the row as
 * `STARTUP REFUSE` and proceeds. If Ktor ever ships CIO Server HTTPS,
 * this actual will start producing real bench numbers automatically.
 */
internal actual fun ApplicationEngine.Configuration.cioConfigureConnector(
    config: BenchmarkConfig,
) {
    if (config.tls != null) {
        val keyStore = buildBenchmarkKeyStore()
        sslConnector(
            keyStore = keyStore,
            keyAlias = BENCHMARK_KEY_ALIAS,
            keyStorePassword = { BENCHMARK_KEY_PASSWORD },
            privateKeyPassword = { BENCHMARK_KEY_PASSWORD },
        ) {
            port = config.port
        }
    } else {
        connector { port = config.port }
    }
}
