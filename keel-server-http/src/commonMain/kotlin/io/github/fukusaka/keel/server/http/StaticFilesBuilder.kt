package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.server.KeelServerDsl

/**
 * Per-mount configuration for the `staticAssets` / `staticFiles` /
 * `staticFile` DSL.
 *
 * Each mount carries its own [mimeTypes] and [etag] settings; the block
 * is optional and defaults to [ContentTypeResolver.Default] /
 * [ETagGenerator.Default].
 */
@KeelServerDsl
public class StaticFilesBuilder internal constructor() {

    /**
     * Resolver for the `Content-Type` of served files. Defaults to
     * [ContentTypeResolver.Default].
     */
    public var mimeTypes: ContentTypeResolver = ContentTypeResolver.Default

    /**
     * Generator for the `ETag` header of served files. Defaults to
     * [ETagGenerator.Default]; set [ETagGenerator.None] to disable
     * ETag-based conditional GET.
     */
    public var etag: ETagGenerator = ETagGenerator.Default
}
