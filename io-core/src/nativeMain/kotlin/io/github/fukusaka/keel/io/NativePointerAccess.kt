package io.github.fukusaka.keel.io

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Provides access to the raw native pointer backing a [NativeBuf].
 *
 * Implemented by all Native-platform [NativeBuf] implementations
 * ([HeapNativeBuf], and engine-specific implementations like
 * RingBufferNativeBuf) to enable zero-copy I/O with POSIX syscalls.
 *
 * Engine modules access the pointer via the [NativeBuf.unsafePointer]
 * extension property, which casts to this interface.
 */
@OptIn(ExperimentalForeignApi::class)
interface NativePointerAccess {
    /** Raw pointer to the underlying native memory. */
    val unsafePointer: CPointer<ByteVar>
}

/**
 * Extension property for engine-layer zero-copy I/O.
 *
 * Exposes the raw `CPointer<ByteVar>` from any [NativeBuf] that
 * implements [NativePointerAccess]. Engine modules use this to pass
 * buffer memory directly to POSIX syscalls.
 */
@OptIn(ExperimentalForeignApi::class)
val NativeBuf.unsafePointer: CPointer<ByteVar>
    get() = (this as NativePointerAccess).unsafePointer
