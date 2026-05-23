package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Returns the raw byte pointer that backs this [SegmentBacking].
 *
 * Public so downstream engine integrations (currently the multi-seg
 * IoBuf PoC scatter-gather write paths in `engine-kqueue` /
 * `engine-epoll` / `engine-io-uring`) can reach the platform memory
 * carrier without casting to keel-io's internal [NativeBacking]. The
 * concrete `NativeBacking` impls (`NativeHeapBacking` /
 * `ExternalNativeBacking`) stay `internal`; this extension is the
 * only public hand-off point.
 *
 * **Throws** [ClassCastException] when called on a non-Native
 * backing — every Native-side allocation in keel-io produces a
 * [NativeBacking], so this only triggers for foreign
 * `SegmentBacking` implementations that should not be reaching
 * Native code paths.
 */
@OptIn(ExperimentalForeignApi::class)
fun SegmentBacking.asNativePointer(): CPointer<ByteVar> = (this as NativeBacking).base
