package io.github.fukusaka.keel.server.http

/**
 * Generates the `ETag` header value for an [Asset].
 *
 * The built-in [Default] derives a weak validator from the asset's size
 * and modification time; [None] disables ETag entirely. A custom
 * generator can be supplied per static-file mount via the
 * `staticFiles { etag = ... }` DSL block — a content-hash strong ETag,
 * for example, can read bytes through [Asset.open].
 *
 * **Implementations must not read [Asset.etag]**: that property is itself
 * backed by an [ETagGenerator] and reading it would recurse.
 */
public fun interface ETagGenerator {

    /**
     * Returns the `ETag` header value for [asset] — including the
     * surrounding quotes and any `W/` prefix — or null to disable the
     * `ETag` header for this asset.
     */
    public fun generate(asset: Asset): String?

    /** Built-in generators. */
    public companion object {

        /**
         * Weak validator `W/"<mtime-epoch-millis hex>-<size hex>"`.
         *
         * Returns null when [Asset.lastModified] is null. The validator
         * is weak: size + mtime cannot prove byte-for-byte identity
         * (mtime granularity, two writes within one tick, mtime resets),
         * so a strong tag is intentionally not claimed. A weak validator
         * is sufficient for `If-None-Match` (which always compares
         * weakly).
         */
        public val Default: ETagGenerator = ETagGenerator { asset ->
            val modified = asset.lastModified ?: return@ETagGenerator null
            val millisHex = modified.toEpochMilliseconds().toString(HEX_RADIX)
            val sizeHex = asset.size.toString(HEX_RADIX)
            "W/\"$millisHex-$sizeHex\""
        }

        /** Disables the `ETag` header — [generate] always returns null. */
        public val None: ETagGenerator = ETagGenerator { null }

        /** Radix for the hex-encoded mtime and size components of the weak validator. */
        private const val HEX_RADIX = 16
    }
}
