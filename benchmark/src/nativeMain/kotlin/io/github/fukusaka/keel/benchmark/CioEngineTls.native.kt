package io.github.fukusaka.keel.benchmark

import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.connector

/**
 * Native actual for [cioConfigureConnector].
 *
 * Ktor 3.4.1 does not expose a Native `sslConnector` overload
 * (`io.ktor.server.engine.EngineConnectorConfigJvm.kt` defines
 * `sslConnector(keyStore, ...)` only in `jvmMain`; PR
 * ktorio/ktor#2942 introduced a multiplatform `sslConnector(TlsConfig,
 * ...)` based on `ktor-network-tls` but is not in the 3.4.1 release).
 * The Native bench therefore cannot route `--tls` through Ktor's own
 * connector machinery to reach Ktor's runtime
 * `UnsupportedOperationException` at `CIOApplicationEngine.kt:224`.
 *
 * To keep the bench harness's observable behaviour identical across
 * platforms, the Native actual re-throws the **exact same exception
 * type and message** that Ktor's commonMain CIOApplicationEngine
 * raises on JVM — `UnsupportedOperationException("CIO Engine does
 * not currently support HTTPS. Please consider using a different
 * engine if you require HTTPS")`. The bench harness records
 * `STARTUP REFUSE` either way, and the row reads the same regardless
 * of whether the throw originated inside Ktor (JVM) or in this actual
 * (Native). When Ktor ever ships the multiplatform sslConnector or
 * lifts the HTTPS restriction, the Native actual will switch to
 * calling `sslConnector(...)` and the JVM path will start producing
 * real bench numbers automatically — both surfaces converge on the
 * same upstream contract.
 */
internal actual fun ApplicationEngine.Configuration.cioConfigureConnector(
    config: BenchmarkConfig,
) {
    if (config.tls != null) {
        throw UnsupportedOperationException(
            "CIO Engine does not currently support HTTPS. Please " +
                "consider using a different engine if you require HTTPS"
        )
    }
    connector { port = config.port }
}
