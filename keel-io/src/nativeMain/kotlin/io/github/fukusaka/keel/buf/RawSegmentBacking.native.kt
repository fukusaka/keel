package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.nativeHeap

/**
 * Native [RawSegmentBacking] over a `CPointer<ByteVar>`.
 *
 * The pointer is either `nativeHeap`-owned (allocated by the allocator
 * via `nativeHeap.allocArray<ByteVar>`, freed by [free]) or external
 * (wrapping caller-owned memory, in which case [free] is a no-op — the
 * external owner reclaims it).
 *
 * @property base       Pointer to the raw memory region.
 * @property ownsMemory Whether [free] should release [base].
 */
@OptIn(ExperimentalForeignApi::class)
internal actual class RawSegmentBacking(
    val base: CPointer<ByteVar>,
    private val ownsMemory: Boolean,
) {
    private var freed = false

    /**
     * Releases the `nativeHeap` allocation when this backing owns it.
     *
     * Idempotent; a no-op for external (non-owning) backings.
     */
    actual fun free() {
        if (!freed) {
            freed = true
            if (ownsMemory) {
                nativeHeap.free(base.rawValue)
            }
        }
    }
}
