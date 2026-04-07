@file:Suppress("unused")

package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.tls.awslc.AwsLcCodecFactory
import kotlin.native.EagerInitialization

/** Auto-register AWS-LC TLS backend at process startup. */
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val nativeTlsInit: Unit = registerTlsProvider { backend ->
    when (backend) {
        "awslc" -> AwsLcCodecFactory()
        else -> error("This binary was built with -Ptls-backend=awslc. Requested: $backend")
    }
}
