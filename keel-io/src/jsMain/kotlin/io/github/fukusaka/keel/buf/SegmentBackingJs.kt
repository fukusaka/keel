package io.github.fukusaka.keel.buf

import org.khronos.webgl.Int8Array

/**
 * Returns the underlying [Int8Array] for the JS backing.
 *
 * Used by the JS engine (`engine-nodejs`) when constructing scatter-
 * gather writes over multi-segment [IoBuf] chains — Node's `Socket.write`
 * accepts arrays of `Buffer` / typed-array slices, so this gives the
 * engine a direct handle without the extra copy that the single-segment
 * path historically required.
 *
 * @throws ClassCastException if the backing is not a JS typed-array
 *   backing — currently impossible (only [Int8ArrayBacking] implements
 *   [SegmentBacking] on JS), reserved for future carriers.
 */
public fun SegmentBacking.asInt8Array(): Int8Array =
    (this as Int8ArrayBacking).base
