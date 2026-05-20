package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.nativeHeap

/**
 * Native [RawSegmentBacking] variant exposing the platform memory as a
 * `CPointer<ByteVar>`.
 *
 * The two concrete provenances ([NativeHeapBacking] and
 * [ExternalNativeBacking]) implement [NativeBacking] so [NativeIoBuf]
 * can read `base` polymorphically without caring which provenance it
 * holds.
 *
 * @property base Pointer to the raw memory region.
 */
@OptIn(ExperimentalForeignApi::class)
internal interface NativeBacking : RawSegmentBacking {
    val base: CPointer<ByteVar>
}

/**
 * `nativeHeap`-owned [RawSegmentBacking].
 *
 * Allocated via `nativeHeap.allocArray<ByteVar>` by the allocator
 * ([SlabAllocator], or the default unpooled allocator inside
 * [NativeIoBuf]). [free] releases the allocation; it is idempotent so
 * the same backing can be safely freed at most once even if a teardown
 * path races (single-EL thread-safety still applies — see
 * [RawSegmentBacking]'s contract).
 *
 * @property base Pointer to the `nativeHeap` allocation.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NativeHeapBacking(
    override val base: CPointer<ByteVar>,
) : NativeBacking {
    private var freed = false

    override fun free() {
        if (!freed) {
            freed = true
            nativeHeap.free(base.rawValue)
        }
    }
}

/**
 * [RawSegmentBacking] wrapping a caller-owned native memory region.
 *
 * Used by `NativeIoBuf.wrapExternal` to expose a pinned `ByteArray` or
 * any other caller-supplied region as an [IoBuf]. The external owner
 * reclaims the memory, so [free] is a no-op.
 *
 * @property base Pointer to the externally-owned region.
 */
@OptIn(ExperimentalForeignApi::class)
internal class ExternalNativeBacking(
    override val base: CPointer<ByteVar>,
) : NativeBacking {
    override fun free() {
        // External owner reclaims the memory; nothing to free here.
    }
}
