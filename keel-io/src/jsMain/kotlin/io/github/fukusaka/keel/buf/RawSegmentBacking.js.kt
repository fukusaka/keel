package io.github.fukusaka.keel.buf

import org.khronos.webgl.Int8Array

/**
 * JS [RawSegmentBacking] over an [Int8Array].
 *
 * V8's garbage collector reclaims the backing `ArrayBuffer`, so [free]
 * is a no-op regardless of whether the backing was produced by
 * [JsRawMemorySource] or wraps an externally-supplied array.
 *
 * @property base The [Int8Array] holding the raw memory region.
 */
internal actual class RawSegmentBacking(
    val base: Int8Array,
) {
    /** No-op: the [Int8Array] is GC-reclaimed by V8. */
    actual fun free() {
        // Int8Array is GC-managed; nothing to free.
    }
}
