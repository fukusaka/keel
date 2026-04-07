@file:Suppress("unused")

package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.tls.openssl.OpenSslCodecFactory
import kotlin.native.EagerInitialization

/** Auto-register OpenSSL TLS backend at process startup. */
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val nativeTlsInit: Unit = registerTlsProvider { backend ->
    when (backend) {
        "openssl" -> OpenSslCodecFactory()
        else -> error("This binary was built with -Ptls-backend=openssl. Requested: $backend")
    }
}
