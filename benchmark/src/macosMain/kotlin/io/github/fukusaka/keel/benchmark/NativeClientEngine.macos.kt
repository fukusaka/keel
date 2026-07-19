package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.engine.kqueue.KqueueEngine
import io.github.fukusaka.keel.engine.nwconnection.NwEngine

/**
 * macOS engines the client benchmark can drive.
 *
 * Both macOS transports are offered because they are structurally different on
 * the client side, not just two spellings of the same thing: kqueue is keel's
 * own readiness-driven loop, while NWConnection hands the connection to
 * Network.framework and receives on a dispatch queue. Which one a client should
 * prefer is exactly the question a client benchmark exists to answer, and
 * neither had ever been measured in that role.
 *
 * A type this host cannot serve is rejected rather than silently falling back
 * to the default, so a run started with another platform's engine name fails
 * where it is written instead of measuring something else.
 */
internal actual fun nativeClientEngineName(clientType: String): String = when (clientType) {
    "keel", "keel-kqueue" -> KQUEUE
    "keel-nwconnection" -> NWCONNECTION
    else -> error(
        "unsupported --client-type='$clientType' on macOS " +
            "(expected keel, keel-kqueue or keel-nwconnection)",
    )
}

/**
 * Builds the named macOS engine (kqueue / NWConnection). The name has already been
 * validated by [nativeClientEngineName], so an unknown one here is a wiring
 * error rather than bad operator input.
 */
internal actual fun createNativeClientEngine(engineName: String): StreamEngine {
    val config = IoEngineConfig(loggerFactory = benchmarkLoggerFactory())
    return when (engineName) {
        KQUEUE -> KqueueEngine(config = config)
        NWCONNECTION -> NwEngine(config = config)
        else -> error("unknown macOS client engine '$engineName'")
    }
}

private const val KQUEUE = "kqueue"
private const val NWCONNECTION = "nwconnection"
