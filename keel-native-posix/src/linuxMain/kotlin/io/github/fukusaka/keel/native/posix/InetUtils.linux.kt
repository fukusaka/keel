package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValuesRef
import kotlinx.cinterop.ExperimentalForeignApi
import posix_inet.keel_inet_ntop
import posix_inet.keel_inet_pton

@OptIn(ExperimentalForeignApi::class)
actual fun inetPton(af: Int, src: String, dst: CValuesRef<*>): Int =
    keel_inet_pton(af, src, dst)

@OptIn(ExperimentalForeignApi::class)
actual fun inetNtop(af: Int, src: CValuesRef<*>, dst: CPointer<ByteVar>, size: UInt): CPointer<ByteVar>? =
    keel_inet_ntop(af, src, dst, size)
