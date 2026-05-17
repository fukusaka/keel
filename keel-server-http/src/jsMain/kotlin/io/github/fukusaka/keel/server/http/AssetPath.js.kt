package io.github.fukusaka.keel.server.http

import kotlin.time.Instant

/** Minimal external view of the Node.js `fs` module's synchronous metadata calls. */
@JsModule("fs")
@JsNonModule
private external object NodeFs {
    fun statSync(path: String): NodeStats
    fun realpathSync(path: String): String
}

/** The subset of `fs.Stats` consulted here — the modification time in epoch millis. */
private external interface NodeStats {
    val mtimeMs: Double
}

/**
 * Reads the file's modification time via Node's `fs.statSync().mtimeMs`,
 * returning null when the file is missing or `statSync` throws.
 */
internal actual fun assetLastModified(path: String): Instant? = try {
    Instant.fromEpochMilliseconds(NodeFs.statSync(path).mtimeMs.toLong())
} catch (_: Throwable) {
    null
}

/**
 * Canonicalizes the path with Node's `fs.realpathSync`, resolving
 * symlinks and `.`/`..`; returns null when the path does not exist.
 */
internal actual fun canonicalizeAssetPath(path: String): String? = try {
    NodeFs.realpathSync(path)
} catch (_: Throwable) {
    null
}
