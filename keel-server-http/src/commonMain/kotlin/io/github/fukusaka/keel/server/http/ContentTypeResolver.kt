package io.github.fukusaka.keel.server.http

/**
 * Resolves a content type from a file name, typically by its extension.
 *
 * The built-in [Default] is backed by an extension → content-type table;
 * a custom resolver can be supplied per static-file mount via the
 * `staticFiles { mimeTypes = ... }` DSL block.
 */
public fun interface ContentTypeResolver {

    /**
     * Returns the content type for [fileName], or null when the
     * extension is unknown. The serve layer falls back to
     * `application/octet-stream` for a null result.
     */
    public fun resolve(fileName: String): String?

    /** Built-in resolvers. */
    public companion object {

        /**
         * Extension → content-type table covering the common web asset
         * types. Lookup is case-insensitive on the extension; text types
         * carry an explicit `charset=utf-8`.
         */
        public val Default: ContentTypeResolver = ContentTypeResolver { fileName ->
            val dot = fileName.lastIndexOf('.')
            if (dot < 0 || dot == fileName.lastIndex) {
                null
            } else {
                EXTENSION_TABLE[fileName.substring(dot + 1).lowercase()]
            }
        }

        /** Maps a lowercase file extension (without the dot) to its content type. */
        private val EXTENSION_TABLE: Map<String, String> = mapOf(
            "html" to "text/html; charset=utf-8",
            "htm" to "text/html; charset=utf-8",
            "css" to "text/css; charset=utf-8",
            "js" to "text/javascript; charset=utf-8",
            "mjs" to "text/javascript; charset=utf-8",
            "json" to "application/json",
            "txt" to "text/plain; charset=utf-8",
            "csv" to "text/csv; charset=utf-8",
            "xml" to "application/xml",
            "pdf" to "application/pdf",
            "wasm" to "application/wasm",
            "png" to "image/png",
            "jpg" to "image/jpeg",
            "jpeg" to "image/jpeg",
            "gif" to "image/gif",
            "svg" to "image/svg+xml",
            "webp" to "image/webp",
            "ico" to "image/x-icon",
            "bmp" to "image/bmp",
            "woff" to "font/woff",
            "woff2" to "font/woff2",
            "ttf" to "font/ttf",
            "otf" to "font/otf",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "mp3" to "audio/mpeg",
            "zip" to "application/zip",
        )
    }
}
