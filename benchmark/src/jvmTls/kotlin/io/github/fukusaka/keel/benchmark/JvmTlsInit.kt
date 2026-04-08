@file:Suppress("unused")

package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.engine.netty.NettySslInstaller
import io.github.fukusaka.keel.tls.jsse.JsseTlsCodecFactory

/**
 * JVM TLS provider registration.
 *
 * Loaded via reflection from [initJvmTlsProvider] so that builds
 * without `-Ptls` compile without the `tls-jsse` dependency.
 */
internal object JvmTlsInit {
    fun register() {
        registerTlsProvider { backend ->
            when (backend) {
                "jsse" -> JsseTlsCodecFactory()
                else -> error("Unsupported TLS backend on JVM: $backend (available: jsse)")
            }
        }
        registerTlsInstallerProvider { installer ->
            when (installer) {
                "netty" -> NettySslInstaller()
                else -> error("Unsupported TLS installer on JVM: $installer (available: keel, netty)")
            }
        }
    }
}
