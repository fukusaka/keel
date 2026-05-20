package io.github.fukusaka.keel.buf

import org.khronos.webgl.Int8Array

/**
 * JS [RawSegmentBacking] over an [Int8Array].
 *
 * Backs every JS-side allocation path: [TypedArrayIoBuf]
 * allocator-allocated primary views and `TypedArrayIoBuf.wrapExternal`
 * callers. V8's garbage collector reclaims the backing `ArrayBuffer`
 * regardless of provenance, so [free] is a single no-op — there is no
 * allocator-vs-external split on JS (compare with the Native side's
 * [NativeHeapBacking] / [ExternalNativeBacking]).
 *
 * @property base The [Int8Array] holding the raw memory region.
 */
internal class Int8ArrayBacking(
    val base: Int8Array,
) : RawSegmentBacking {
    /** No-op: the [Int8Array] is GC-reclaimed by V8. */
    override fun free() {
        // Int8Array is GC-managed; nothing to free.
    }
}
