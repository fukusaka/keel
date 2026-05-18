package io.github.fukusaka.keel.server.http

import kotlinx.io.Buffer
import kotlinx.io.RawSource
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.time.Instant

/** Number of bytes discarded per skip iteration when serving a non-zero offset. */
private const val SKIP_CHUNK = 8192L

/**
 * An [AssetSource] backed by a directory on a [FileSystem].
 *
 * This is the security boundary for filesystem-served static content.
 * [resolve] treats its argument as untrusted and applies the 5-layer
 * path-traversal defense:
 *
 * 1. The serve layer percent-decodes the request remainder exactly once
 *    before calling [resolve] (double-encoding is therefore inert).
 * 2. A NUL character in the decoded path is rejected.
 * 3. Lexical normalization with root containment — see
 *    [normalizeRelativeAssetPath].
 * 4. Symlink containment — the candidate is canonicalized
 *    ([canonicalizeAssetPath]) and re-checked against the canonical root
 *    computed once at construction.
 * 5. Regular-file check — directories, FIFOs and devices resolve to null.
 *
 * Every rejection yields null; the serve layer answers a rejected path
 * with `404` so directory structure is not leaked.
 *
 * The [FileSystem] is injectable so tests can substitute a temp
 * directory or a fake.
 *
 * @param fileSystem filesystem the [root] lives on; defaults to [SystemFileSystem].
 * @param root directory path that confines every resolved asset.
 * @param contentTypeResolver resolves the [Asset.contentType] from the file name.
 * @param etagGenerator generates the [Asset.etag]; defaults to [ETagGenerator.Default].
 */
public class FilesystemAssetSource(
    private val fileSystem: FileSystem = SystemFileSystem,
    root: String,
    private val contentTypeResolver: ContentTypeResolver = ContentTypeResolver.Default,
    private val etagGenerator: ETagGenerator = ETagGenerator.Default,
) : AssetSource {

    /** The canonical (symlink-resolved) root, computed once; null when [root] does not exist. */
    private val canonicalRoot: String? = canonicalizeAssetPath(root)

    override fun resolve(path: String): Asset? {
        val canonicalRootPath = canonicalRoot ?: return null
        // Layers 2 + 3: NUL rejection and lexical normalization.
        val relative = normalizeRelativeAssetPath(path) ?: return null
        val candidate = if (relative.isEmpty()) Path(canonicalRootPath) else Path(canonicalRootPath, relative)
        // Layer 4: symlink containment — the real path must stay under the canonical root.
        val canonicalCandidate = canonicalizeAssetPath(candidate.toString()) ?: return null
        if (!isWithinRoot(canonicalCandidate, canonicalRootPath)) return null
        // Layer 5: regular-file check.
        val resolved = Path(canonicalCandidate)
        val metadata = fileSystem.metadataOrNull(resolved) ?: return null
        if (!metadata.isRegularFile) return null
        return FilesystemAsset(
            fileSystem = fileSystem,
            path = resolved,
            size = metadata.size,
            contentType = contentTypeResolver.resolve(resolved.name),
            lastModified = assetLastModified(canonicalCandidate),
            etagGenerator = etagGenerator,
        )
    }

    private companion object {

        /** True when [candidate] is the root itself or a descendant of [root]. */
        fun isWithinRoot(candidate: String, root: String): Boolean {
            if (candidate == root) return true
            val prefix = if (root.endsWith('/')) root else "$root/"
            return candidate.startsWith(prefix)
        }
    }
}

/**
 * An [Asset] over a regular file on a [FileSystem].
 *
 * Metadata is captured eagerly at resolution time; the [etag] is
 * computed lazily on first access so a request that never inspects the
 * ETag never runs the generator.
 */
private class FilesystemAsset(
    private val fileSystem: FileSystem,
    private val path: Path,
    override val size: Long,
    override val contentType: String?,
    override val lastModified: Instant?,
    etagGenerator: ETagGenerator,
) : Asset {

    override val etag: String? by lazy { etagGenerator.generate(this) }

    override fun open(offset: Long, length: Long): RawSource {
        val source = fileSystem.source(path)
        // Whole-asset open from the start: no skipping or bounding needed.
        if (offset <= 0 && length >= size) return source
        // Naive offset support: discard the leading bytes. A seek-based
        // fast path is a future optimisation.
        val buffered = source.buffered()
        var remaining = offset
        while (remaining > 0) {
            val step = minOf(remaining, SKIP_CHUNK)
            if (!buffered.request(step)) break
            buffered.skip(step)
            remaining -= step
        }
        // Bound the result to exactly [length] bytes so a partial-range
        // open never streams past the requested window.
        return BoundedRawSource(buffered, length)
    }
}

/**
 * A [RawSource] that exposes at most [limit] bytes of [delegate], then
 * reports end-of-input. Used to confine a partial-range asset open to
 * the requested byte window.
 */
private class BoundedRawSource(
    private val delegate: RawSource,
    private val limit: Long,
) : RawSource {

    private var remaining: Long = limit

    override fun readAtMostTo(sink: Buffer, byteCount: Long): Long {
        if (remaining <= 0L) return -1L
        val want = minOf(byteCount, remaining)
        val read = delegate.readAtMostTo(sink, want)
        if (read > 0L) remaining -= read
        return read
    }

    override fun close() = delegate.close()
}
