package io.github.fukusaka.keel.engine.iouring

import io_uring.io_uring
import io_uring.keel_register_buffers
import io_uring.keel_unregister_buffers
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set

/**
 * Production [IoUringRegisteredBufferOps] backed by the `keel_register_buffers`
 * / `keel_unregister_buffers` C wrappers in `io_uring.def`. Stateless singleton.
 *
 * The `memScoped` / `allocArray` dance that builds the parallel base /
 * length arrays for the kernel lives here so [StaticRegisteredBufferRegistry]
 * (and its seam tests) deal only in `(pointer, capacity)` pairs.
 */
@OptIn(ExperimentalForeignApi::class)
internal object PosixIoUringRegisteredBufferOps : IoUringRegisteredBufferOps {

    override fun registerBuffers(
        ring: CPointer<io_uring>,
        buffers: List<Pair<CPointer<ByteVar>, Int>>,
    ): Int = memScoped {
        val bases = allocArray<COpaquePointerVar>(buffers.size)
        val lens = allocArray<ULongVar>(buffers.size)
        for ((i, pair) in buffers.withIndex()) {
            val (ptr, cap) = pair
            bases[i] = ptr
            lens[i] = cap.convert()
        }
        keel_register_buffers(ring, bases.reinterpret(), lens.reinterpret(), buffers.size)
    }

    override fun unregisterBuffers(ring: CPointer<io_uring>): Int =
        keel_unregister_buffers(ring)
}
