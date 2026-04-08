@file:Suppress("unused")

package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.engine.nwconnection.NwTlsInstaller
import kotlin.native.EagerInitialization

/** Auto-register NWConnection listener-level TLS installer at process startup. */
@OptIn(ExperimentalStdlibApi::class)
@EagerInitialization
private val macosTlsInstallerInit: Unit = registerTlsInstallerProvider { installer ->
    when (installer) {
        "nwconnection" -> NwTlsInstaller
        else -> error("Unsupported TLS installer on macOS: $installer (available: keel, nwconnection)")
    }
}
