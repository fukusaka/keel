@file:OptIn(ExperimentalForeignApi::class, UnsafeIoBufApi::class)

package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.buf.wrapExternalNativePtr
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.plus
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import nwconnection.keel_nw_dispatch_data_release
import nwconnection.keel_nw_test_dispatch_handle
import nwconnection.keel_nw_test_dispatch_handle_copyonly
import nwconnection.keel_nw_test_make_data_single
import kotlin.time.TimeSource

/**
 * macOS actual for [runNwRecvCostBench]. See common KDoc for purpose
 * and methodology.
 */
@OptIn(UnsafeIoBufApi::class)
actual fun runNwRecvCostBench(): Boolean {
    val sizes = intArrayOf(13, 1024, 8192, 65_536)
    val iters = 100_000

    println("=== NW receive cost: wrap-path (production) vs copy-path (baseline) ===")
    println("Methodology: pre-created single-region dispatch_data_t, drive")
    println("keel_nw_dispatch_received N=$iters times per scenario, median of 5 runs.")
    println("Includes Kotlin-side IoBuf wrap allocation and release in the wrap-path —")
    println("the cost component that the original investigation micro-bench missed.")
    println()
    println("size_bytes  segwrap_ns  engdir_ns  copy_ns   eng_vs_copy")

    for (size in sizes) {
        val segWrapNs = medianRun(5) { measureSegmentBackedWrap(size, iters) }
        val engDirNs = medianRun(5) { measureEngineDirect(size, iters) }
        val copyNs = medianRun(5) { measureCopy(size, iters) }
        val engDelta = engDirNs - copyNs
        println(
            "${pad(size.toString(), 10)}  ${oneDec(segWrapNs, 9)}  ${oneDec(engDirNs, 8)}" +
                "  ${oneDec(copyNs, 7)}  ${oneDecSigned(engDelta, 9)}",
        )
    }
    println()
    println("Note: production end-to-end A/B (wrk loopback) is in")
    println("benchmark/results-summary/2026-05-20-nw-zerocopy-recv-ab.md;")
    println("the numbers above isolate the receive-callback cost from the")
    println("rest of the request pipeline (codec parse, response write, etc).")
    return true
}

private fun medianRun(n: Int, action: () -> Double): Double {
    val samples = DoubleArray(n) { action() }
    samples.sort()
    return samples[n / 2]
}

private fun measureSegmentBackedWrap(size: Int, iters: Int): Double {
    val payload = ByteArray(size) { ((it + 1) and 0xFF).toByte() }
    return payload.usePinned { pinned ->
        val handle = checkNotNull(
            keel_nw_test_make_data_single(pinned.addressOf(0), size.toUInt()),
        ) { "make_data_single returned null" }
        try {
            // The fallback buffer is pre-allocated once and reused across
            // iterations — mirrors NwIoTransport.spareFallbackBuf so the
            // measurement matches production behaviour.
            val fallbackBuf = DefaultAllocator.allocate(maxOf(size, 8192))
            try {
                val ptr = (fallbackBuf.unsafePointer + fallbackBuf.writerIndex)!!
                val ctx = WrapCallbackCtx(0)
                val ref = StableRef.create(ctx)
                try {
                    // Warmup
                    repeat(2_000) {
                        keel_nw_test_dispatch_handle(
                            handle, ptr, fallbackBuf.writableBytes.toUInt(),
                            0, segmentBackedWrapCallback, ref.asCPointer(),
                        )
                    }
                    val mark = TimeSource.Monotonic.markNow()
                    repeat(iters) {
                        keel_nw_test_dispatch_handle(
                            handle, ptr, fallbackBuf.writableBytes.toUInt(),
                            0, segmentBackedWrapCallback, ref.asCPointer(),
                        )
                    }
                    val elapsed = mark.elapsedNow()
                    return elapsed.inWholeNanoseconds.toDouble() / iters
                } finally {
                    ref.dispose()
                }
            } finally {
                fallbackBuf.release()
            }
        } finally {
            keel_nw_dispatch_data_release(handle)
        }
    }
}

/**
 * Measures the engine-direct wrap path used by production after
 * PR #583 (engine-direct [DispatchDataIoBuf]). The callback
 * allocates a tiny holder class and immediately runs the handle
 * release — same allocation count + same cinterop release call as
 * production, without needing to import `DispatchDataIoBuf`
 * (`internal` to keel-engine-nwconnection).
 */
private fun measureEngineDirect(size: Int, iters: Int): Double {
    val payload = ByteArray(size) { ((it + 1) and 0xFF).toByte() }
    return payload.usePinned { pinned ->
        val handle = checkNotNull(
            keel_nw_test_make_data_single(pinned.addressOf(0), size.toUInt()),
        ) { "make_data_single returned null" }
        try {
            val fallbackBuf = DefaultAllocator.allocate(maxOf(size, 8192))
            try {
                val ptr = (fallbackBuf.unsafePointer + fallbackBuf.writerIndex)!!
                val ctx = EngineDirectCallbackCtx(0)
                val ref = StableRef.create(ctx)
                try {
                    repeat(2_000) {
                        keel_nw_test_dispatch_handle(
                            handle, ptr, fallbackBuf.writableBytes.toUInt(),
                            0, engineDirectCallback, ref.asCPointer(),
                        )
                    }
                    val mark = TimeSource.Monotonic.markNow()
                    repeat(iters) {
                        keel_nw_test_dispatch_handle(
                            handle, ptr, fallbackBuf.writableBytes.toUInt(),
                            0, engineDirectCallback, ref.asCPointer(),
                        )
                    }
                    val elapsed = mark.elapsedNow()
                    return elapsed.inWholeNanoseconds.toDouble() / iters
                } finally {
                    ref.dispose()
                }
            } finally {
                fallbackBuf.release()
            }
        } finally {
            keel_nw_dispatch_data_release(handle)
        }
    }
}

private fun measureCopy(size: Int, iters: Int): Double {
    val payload = ByteArray(size) { ((it + 1) and 0xFF).toByte() }
    return payload.usePinned { pinned ->
        val handle = checkNotNull(
            keel_nw_test_make_data_single(pinned.addressOf(0), size.toUInt()),
        ) { "make_data_single returned null" }
        try {
            // Same setup as wrap path — measure only the callback work
            // difference, not buffer allocation.
            val fallbackBuf = DefaultAllocator.allocate(maxOf(size, 8192))
            try {
                val ptr = (fallbackBuf.unsafePointer + fallbackBuf.writerIndex)!!
                val ctx = CopyCallbackCtx(0)
                val ref = StableRef.create(ctx)
                try {
                    repeat(2_000) {
                        keel_nw_test_dispatch_handle_copyonly(
                            handle, ptr, fallbackBuf.writableBytes.toUInt(),
                            0, copyCallback, ref.asCPointer(),
                        )
                    }
                    val mark = TimeSource.Monotonic.markNow()
                    repeat(iters) {
                        keel_nw_test_dispatch_handle_copyonly(
                            handle, ptr, fallbackBuf.writableBytes.toUInt(),
                            0, copyCallback, ref.asCPointer(),
                        )
                    }
                    val elapsed = mark.elapsedNow()
                    return elapsed.inWholeNanoseconds.toDouble() / iters
                } finally {
                    ref.dispose()
                }
            } finally {
                fallbackBuf.release()
            }
        } finally {
            keel_nw_dispatch_data_release(handle)
        }
    }
}

private class WrapCallbackCtx(var counter: Int)
private class EngineDirectCallbackCtx(var counter: Int)
private class CopyCallbackCtx(var counter: Int)

/**
 * Holder class with the same shape as `DispatchDataIoBuf` —
 * 3 reference fields + 1 int field — used by [engineDirectCallback]
 * to simulate the engine-direct allocation cost without importing
 * the internal `DispatchDataIoBuf` class. Allocation cost is
 * structurally equivalent (small Kotlin/Native heap object).
 */
private class EngineDirectHolder(
    val ptr: COpaquePointer,
    val len: Int,
    val handle: COpaquePointer,
) {
    fun release() {
        keel_nw_dispatch_data_release(handle)
    }
}

private val segmentBackedWrapCallback = staticCFunction {
        zcHandle: COpaquePointer?,
        zcPtr: COpaquePointer?,
        len: UInt,
        _: Int, _: Int,
        ctx: COpaquePointer? ->
    // Production-equivalent work on the zero-copy branch:
    //   wrap as IoBuf via wrapExternalNativePtr
    //   release the IoBuf — triggers keel_nw_dispatch_data_release
    if (zcHandle != null && zcPtr != null) {
        @OptIn(ExperimentalForeignApi::class)
        val buf = wrapExternalNativePtr(zcPtr.reinterpret<ByteVar>(), len.toInt()) {
            keel_nw_dispatch_data_release(zcHandle)
        }
        buf.release()
    }
    // Bump counter so the compiler doesn't elide the callback.
    if (ctx != null) {
        val c = ctx.asStableRef<WrapCallbackCtx>().get()
        c.counter++
    }
}

private val engineDirectCallback = staticCFunction {
        zcHandle: COpaquePointer?,
        zcPtr: COpaquePointer?,
        len: UInt,
        _: Int, _: Int,
        ctx: COpaquePointer? ->
    if (zcHandle != null && zcPtr != null) {
        val holder = EngineDirectHolder(zcPtr, len.toInt(), zcHandle)
        holder.release()
    }
    if (ctx != null) {
        val c = ctx.asStableRef<EngineDirectCallbackCtx>().get()
        c.counter++
    }
}

private val copyCallback = staticCFunction {
        _: COpaquePointer?,
        _: COpaquePointer?,
        len: UInt,
        _: Int, _: Int,
        ctx: COpaquePointer? ->
    // Production-equivalent work on the copy branch: bytes are already
    // in fallbackBuf, just update writerIndex. NwIoTransport actually
    // delivers fallbackBuf to onRead — measuring just the post-copy
    // accounting is sufficient since pipeline-side work is identical
    // for both paths.
    if (ctx != null) {
        val c = ctx.asStableRef<CopyCallbackCtx>().get()
        c.counter += len.toInt()
    }
}

// --- Number formatting helpers (no String.format in K/N stdlib) ---

private fun pad(s: String, width: Int): String =
    if (s.length >= width) s else " ".repeat(width - s.length) + s

private fun oneDec(d: Double, width: Int): String {
    val rounded = (d * 10.0).toLong()
    val whole = rounded / 10
    val frac = (if (rounded < 0) -rounded else rounded) % 10
    return pad("$whole.$frac", width)
}

private fun oneDecSigned(d: Double, width: Int): String {
    val sign = if (d >= 0) "+" else ""
    val rounded = (d * 10.0).toLong()
    val whole = rounded / 10
    val frac = (if (rounded < 0) -rounded else rounded) % 10
    return pad("$sign$whole.$frac", width)
}

private fun threeDec(d: Double): String {
    if (d.isNaN()) return "NaN"
    val rounded = (d * 1000.0).toLong()
    val whole = rounded / 1000
    val frac = (if (rounded < 0) -rounded else rounded) % 1000
    return "$whole.${frac.toString().padStart(3, '0')}"
}
