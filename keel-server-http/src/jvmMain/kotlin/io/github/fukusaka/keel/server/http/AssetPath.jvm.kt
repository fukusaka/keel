package io.github.fukusaka.keel.server.http

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Paths
import kotlin.time.Instant

/**
 * Reads the file's last-modified time via `java.nio.file.Files`,
 * returning null when the file is missing or its metadata is
 * unreadable.
 */
internal actual fun assetLastModified(path: String): Instant? = try {
    val millis = Files.getLastModifiedTime(Paths.get(path)).toMillis()
    Instant.fromEpochMilliseconds(millis)
} catch (_: NoSuchFileException) {
    null
} catch (_: IOException) {
    null
}

/**
 * Canonicalizes the path with `Path.toRealPath`, resolving symlinks and
 * `.`/`..`; returns null when the path does not exist.
 */
internal actual fun canonicalizeAssetPath(path: String): String? = try {
    Paths.get(path).toRealPath(*emptyArray<LinkOption>()).toString()
} catch (_: NoSuchFileException) {
    null
} catch (_: IOException) {
    null
}
