package io.github.fukusaka.keel.buf

import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Provides access to the raw native pointer backing a [IoBuf].
 *
 * Implemented by all Native-platform [IoBuf] implementations
 * ([NativeIoBuf], and engine-specific implementations like
 * RingBufferIoBuf) to enable zero-copy I/O with POSIX syscalls.
 *
 * Engine modules access the pointer via the [IoBuf.unsafePointer]
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
 * Exposes the raw `CPointer<ByteVar>` from any [IoBuf] that
 * implements [NativePointerAccess]. Engine modules use this to pass
 * buffer memory directly to POSIX syscalls.
 */
@OptIn(ExperimentalForeignApi::class)
val IoBuf.unsafePointer: CPointer<ByteVar>
    get() = (this as NativePointerAccess).unsafePointer
