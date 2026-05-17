package io.github.fukusaka.keel.server.http

import kotlinx.io.RawSource
import kotlin.time.Instant

/**
 * A resolved static asset — metadata available immediately, body opened
 * lazily on demand (a descriptor, not an open handle).
 *
 * The serve routine processes an asset as: resolve → read metadata →
 * evaluate conditional GET (a `304` finishes without opening the body) →
 * stream the body for a `200` / `206`. Because of this ordering an
 * [Asset] holds no resource itself and is not [AutoCloseable]; only the
 * [RawSource] returned by [open] needs closing.
 *
 * Conditional-GET metadata ([lastModified] / [etag]) is carried by the
 * asset so the serve layer only reads it. A provider that supplies
 * neither degrades gracefully to no conditional-GET support.
 */
public interface Asset {

    /** Asset size in bytes — used for `Content-Length` and Range math. */
    public val size: Long

    /**
     * Content type the provider resolved for this asset (for example
     * `text/html; charset=utf-8`), or null when it could not be
     * determined. The serve layer falls back to
     * `application/octet-stream` when this is null.
     */
    public val contentType: String?

    /**
     * Last-modified instant, or null when the provider cannot supply it.
     * Drives the `Last-Modified` response header and `If-Modified-Since`
     * evaluation.
     */
    public val lastModified: Instant?

    /**
     * The complete `ETag` header value (for example `W/"5f-1a2b"`),
     * including the surrounding quotes and any `W/` weak prefix, or null
     * when ETag support is disabled. Drives the `ETag` response header
     * and `If-None-Match` evaluation.
     */
    public val etag: String?

    /**
     * Opens the asset body for reading, optionally restricted to the
     * byte range `[offset, offset + length)`.
     *
     * The returned [RawSource] is the only closeable resource an [Asset]
     * exposes — the caller must close it after streaming the body.
     *
     * @param offset starting byte offset, defaults to the start of the asset.
     * @param length number of bytes to expose, defaults to the whole asset.
     */
    public fun open(offset: Long = 0, length: Long = size): RawSource
}

/**
 * Supplies [Asset]s for request paths — a directory, and in the future a
 * classpath or an in-memory map.
 */
public interface AssetSource {

    /**
     * Resolves [path] to an [Asset], or null when the asset does not
     * exist, is not a regular file, or the path fails a traversal /
     * containment check.
     *
     * [path] is the already-percent-decoded request remainder; the serve
     * layer decodes exactly once before calling this.
     */
    public fun resolve(path: String): Asset?
}
