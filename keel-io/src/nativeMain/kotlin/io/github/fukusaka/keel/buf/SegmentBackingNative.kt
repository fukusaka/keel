package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Returns the underlying `CPointer<ByteVar>` for the Native backing.
 *
 * Used by Native engines (`engine-kqueue`, `engine-epoll`,
 * `engine-io-uring`) to construct `iovec` arrays for `writev` over
 * multi-segment [IoBuf] chains without copying.
 *
 * Callers compute absolute pointers via `pointer + offset` (kotlinx
 * cinterop pointer arithmetic) using the [SegmentRange.offset] /
 * [SegmentRangeAction] window offset supplied by the iteration API.
 *
 * @throws ClassCastException if the backing is not a Native pointer
 *   backing — currently impossible (both [NativeHeapBacking] and
 *   [ExternalNativeBacking] implement [NativeBacking]), but reserved
 *   for a future provenance that does not expose a `CPointer`.
 */
@OptIn(ExperimentalForeignApi::class)
public fun SegmentBacking.asNativePointer(): CPointer<ByteVar> =
    (this as NativeBacking).base
