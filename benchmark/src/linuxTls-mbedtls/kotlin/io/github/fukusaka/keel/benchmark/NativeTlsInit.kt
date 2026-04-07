@file:Suppress("unused")

package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.tls.mbedtls.MbedTlsCodecFactory
import kotlin.native.EagerInitialization

/** Auto-register Mbed TLS backend at process startup. */
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val nativeTlsInit: Unit = registerTlsProvider { backend ->
    when (backend) {
        "mbedtls" -> MbedTlsCodecFactory()
        else -> error("This binary was built with -Ptls-backend=mbedtls. Requested: $backend")
    }
}
