@file:Suppress("unused")

package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.tls.awslc.AwsLcCodecFactory
import io.github.fukusaka.keel.tls.mbedtls.MbedTlsCodecFactory
import io.github.fukusaka.keel.tls.openssl.OpenSslCodecFactory
import kotlin.native.EagerInitialization

/** Auto-register macOS TLS backends (OpenSSL, AWS-LC, Mbed TLS) at process startup. */
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val macosNativeTlsInit: Unit = registerTlsProvider { backend ->
    when (backend) {
        "openssl" -> OpenSslCodecFactory()
        "awslc" -> AwsLcCodecFactory()
        "mbedtls" -> MbedTlsCodecFactory()
        else -> error("Unsupported TLS backend on macOS: $backend (available: openssl, awslc, mbedtls)")
    }
}
