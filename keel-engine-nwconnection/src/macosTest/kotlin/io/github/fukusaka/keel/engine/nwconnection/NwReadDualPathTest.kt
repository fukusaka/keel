@file:OptIn(ExperimentalForeignApi::class)

package io.github.fukusaka.keel.engine.nwconnection

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import nwconnection.keel_nw_dispatch_data_release
import nwconnection.keel_nw_test_dispatch_concat
import nwconnection.keel_nw_test_dispatch_single
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Regression tests for the dual-path read callback in
 * `nwconnection.def`'s `keel_nw_dispatch_received` — the single-region
 * zero-copy fast path and the multi-region copy fallback.
 *
 * - **Single-region**: the test C helper builds a 1-region
 *   `dispatch_data_t`, drives the production branching logic, and the
 *   Kotlin callback observes a non-NULL `zc_handle` + region pointer.
 *   Bytes read through the pointer must match what was put in. The
 *   handle is released via [keel_nw_dispatch_data_release].
 *
 * - **Multi-region**: a 2-region `dispatch_data_t` (built via
 *   `dispatch_data_create_concat`) drives the same logic and the
 *   callback receives `zc_handle == NULL` + the bytes memcpy'd into
 *   the test-supplied fallback buffer.
 *
 * The loopback NW path always coalesces TCP receives into a single
 * region (verified during the 2026-05-20 investigation), so the
 * multi-region branch is unreachable through `NwEngineReadWriteTest`'s
 * real-socket pattern. This test injects the multi-region case
 * directly to keep that branch covered.
 */
class NwReadDualPathTest {

    @Test
    fun single_region_delivers_zero_copy_handle_and_pointer() {
        val payload = "single-region-payload".encodeToByteArray()
        val result = drive { fallbackBuf, fallbackLen, cb, ctx ->
            payload.usePinned { pinned ->
                keel_nw_test_dispatch_single(
                    pinned.addressOf(0),
                    payload.size.toUInt(),
                    fallbackBuf,
                    fallbackLen,
                    is_complete = 0,
                    cb,
                    ctx,
                )
            }
        }
        val zcHandle = assertNotNull(result.zcHandle, "expected zero-copy handle on single-region path")
        val zcPtr = assertNotNull(result.zcPtr, "expected non-null region pointer")
        assertEquals(payload.size, result.bytesReported)
        // Verify bytes are readable through the handle-retained pointer.
        val readBack = ByteArray(payload.size) { zcPtr[it] }
        assertEquals(payload.toList(), readBack.toList(), "bytes mismatch on zero-copy path")
        // Release the handle.
        keel_nw_dispatch_data_release(zcHandle)
    }

    @Test
    fun multi_region_falls_back_to_memcpy_into_supplied_buffer() {
        val part1 = "header-line\r\n".encodeToByteArray()
        val part2 = "body-content".encodeToByteArray()
        val expected = part1 + part2
        val result = drive(fallbackCapacity = expected.size + 8) { fallbackBuf, fallbackLen, cb, ctx ->
            part1.usePinned { p1 ->
                part2.usePinned { p2 ->
                    keel_nw_test_dispatch_concat(
                        p1.addressOf(0), part1.size.toUInt(),
                        p2.addressOf(0), part2.size.toUInt(),
                        fallbackBuf, fallbackLen,
                        is_complete = 0,
                        cb, ctx,
                    )
                }
            }
        }
        assertNull(result.zcHandle, "multi-region must signal copy path via null zc_handle")
        assertNull(result.zcPtr, "multi-region must signal copy path via null zc_ptr")
        assertEquals(expected.size, result.bytesReported)
        // Verify bytes are in the fallback buffer.
        val fallbackBytes = assertNotNull(result.fallbackBytes, "fallback bytes must be populated on copy path")
        val readBack = ByteArray(expected.size) { fallbackBytes[it] }
        assertEquals(expected.toList(), readBack.toList(), "bytes mismatch on fallback copy path")
    }

    @Test
    fun multi_region_copy_respects_fallback_buffer_capacity() {
        // Combined payload exceeds the fallback buffer; copy must stop
        // at the buffer's capacity (same boundary behaviour as the
        // pre-zero-copy implementation).
        val part1 = ByteArray(40) { 0x41 } // 'A' × 40
        val part2 = ByteArray(40) { 0x42 } // 'B' × 40
        val fallbackCapacity = 50 // less than 80
        val result = drive(fallbackCapacity = fallbackCapacity) { fallbackBuf, fallbackLen, cb, ctx ->
            part1.usePinned { p1 ->
                part2.usePinned { p2 ->
                    keel_nw_test_dispatch_concat(
                        p1.addressOf(0), part1.size.toUInt(),
                        p2.addressOf(0), part2.size.toUInt(),
                        fallbackBuf, fallbackLen,
                        is_complete = 0,
                        cb, ctx,
                    )
                }
            }
        }
        assertNull(result.zcHandle)
        assertEquals(fallbackCapacity, result.bytesReported)
        // First 40 bytes should be 'A', remaining 10 bytes 'B'.
        val fallbackBytes = assertNotNull(result.fallbackBytes, "fallback bytes must be populated on copy path")
        for (i in 0 until 40) {
            assertEquals(0x41.toByte(), fallbackBytes[i], "byte $i should be 'A'")
        }
        for (i in 40 until 50) {
            assertEquals(0x42.toByte(), fallbackBytes[i], "byte $i should be 'B'")
        }
    }

    @Test
    fun is_complete_propagates_through_both_paths() {
        // Single-region path with is_complete=1
        val payload = "x".encodeToByteArray()
        val r1 = drive { fb, fbLen, cb, ctx ->
            payload.usePinned { p ->
                keel_nw_test_dispatch_single(
                    p.addressOf(0),
                    payload.size.toUInt(),
                    fb,
                    fbLen,
                    is_complete = 1,
                    cb,
                    ctx,
                )
            }
        }
        assertTrue(r1.isComplete, "single-region path lost is_complete=true")
        keel_nw_dispatch_data_release(r1.zcHandle)

        // Multi-region path with is_complete=1
        val a = "a".encodeToByteArray()
        val b = "b".encodeToByteArray()
        val r2 = drive { fb, fbLen, cb, ctx ->
            a.usePinned { pa ->
                b.usePinned { pb ->
                    keel_nw_test_dispatch_concat(
                        pa.addressOf(0), a.size.toUInt(),
                        pb.addressOf(0), b.size.toUInt(),
                        fb, fbLen, is_complete = 1, cb, ctx,
                    )
                }
            }
        }
        assertTrue(r2.isComplete, "multi-region path lost is_complete=true")
    }

    // --- Test harness ---

    private class Captured(
        val zcHandle: COpaquePointer?,
        val zcPtr: kotlinx.cinterop.CPointer<ByteVar>?,
        val bytesReported: Int,
        val isComplete: Boolean,
        val failed: Boolean,
        /** Snapshot of the fallback buffer's contents, length == fallbackCapacity. */
        val fallbackBytes: ByteArray?,
    )

    private fun drive(
        fallbackCapacity: Int = 256,
        invoke: (
            fallbackBuf: COpaquePointer,
            fallbackLen: UInt,
            cb: kotlinx.cinterop.CPointer<
                kotlinx.cinterop.CFunction<
                    (
                        COpaquePointer?,
                        COpaquePointer?,
                        UInt,
                        Int,
                        Int,
                        COpaquePointer?,
                    ) -> Unit,
                    >,
                >,
            ctx: COpaquePointer?,
        ) -> Unit,
    ): Captured {
        // Capture callback args into a holder StableRef'd as ctx.
        val holder = Holder()
        val ref = StableRef.create(holder)
        try {
            // Capture state inside memScoped so the fallback array is
            // snapshotted into a Kotlin ByteArray before scoped memory
            // is reclaimed on block exit.
            return memScoped {
                val fallback = allocArray<ByteVar>(fallbackCapacity)
                invoke(fallback, fallbackCapacity.toUInt(), staticCallback, ref.asCPointer())
                val fallbackSnapshot = if (holder.zcHandle == null) {
                    ByteArray(fallbackCapacity) { fallback[it] }
                } else {
                    null
                }
                Captured(
                    zcHandle = holder.zcHandle,
                    zcPtr = holder.zcPtr,
                    bytesReported = holder.bytesReported,
                    isComplete = holder.isComplete,
                    failed = holder.failed,
                    fallbackBytes = fallbackSnapshot,
                )
            }
        } finally {
            ref.dispose()
        }
    }

    private class Holder {
        var zcHandle: COpaquePointer? = null
        var zcPtr: kotlinx.cinterop.CPointer<ByteVar>? = null
        var bytesReported: Int = -1
        var isComplete: Boolean = false
        var failed: Boolean = false
    }

    companion object {
        private val staticCallback = staticCFunction {
                zcHandle: COpaquePointer?,
                zcPtr: COpaquePointer?,
                len: UInt,
                isComplete: Int,
                error: Int,
                ctx: COpaquePointer?,
            ->
            val h = checkNotNull(ctx) { "ctx null" }.asStableRef<Holder>().get()
            h.zcHandle = zcHandle
            h.zcPtr = zcPtr?.reinterpret<ByteVar>()
            h.bytesReported = len.toInt()
            h.isComplete = isComplete != 0
            h.failed = error != 0
        }
    }
}
