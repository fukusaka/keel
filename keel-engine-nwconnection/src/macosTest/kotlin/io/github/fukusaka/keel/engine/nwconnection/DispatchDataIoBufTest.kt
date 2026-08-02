@file:OptIn(ExperimentalForeignApi::class, UnsafeIoBufApi::class)

package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.usePinned
import nwconnection.keel_nw_test_dispatch_handle
import nwconnection.keel_nw_test_make_data_single
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [DispatchDataIoBuf] — the engine-direct IoBuf that
 * wraps a single contiguous region of a retained NWConnection
 * `dispatch_data_t`.
 *
 * The dispatch_data_t handle is synthesised via the test-only C
 * helper `keel_nw_test_make_data_single` and driven through the
 * production `keel_nw_dispatch_received` via `keel_nw_test_dispatch_handle`
 * so the callback receives the same `(zc_handle, zc_ptr, len)` shape
 * the production engine uses. The test then constructs a
 * [DispatchDataIoBuf] from those parameters and exercises the IoBuf
 * contract.
 */
class DispatchDataIoBufTest {

    @Test
    fun bytes_readable_through_the_dispatch_data_region() {
        val payload = "engine-direct-zero-copy".encodeToByteArray()
        withDispatchHandle(payload) { ptr, len, handle ->
            val buf = DispatchDataIoBuf(ptr, len, handle)
            assertEquals(payload.size, buf.capacity)
            assertEquals(0, buf.readerIndex)
            assertEquals(payload.size, buf.writerIndex)
            for (i in payload.indices) {
                assertEquals(payload[i], buf.getByte(i), "byte $i")
            }
            assertTrue(buf.release(), "single release should drop the last ref")
        }
    }

    @Test
    fun retain_release_pair_keeps_handle_alive_until_zero() {
        val payload = "ref-counted".encodeToByteArray()
        withDispatchHandle(payload) { ptr, len, handle ->
            val buf = DispatchDataIoBuf(ptr, len, handle)
            buf.retain() // refCount 1 → 2
            buf.retain() // 2 → 3
            assertEquals(false, buf.release(), "release at refCount=3→2 must not free")
            assertEquals(false, buf.release(), "release at refCount=2→1 must not free")
            // bytes still readable while refcount > 0
            assertEquals(payload[0], buf.getByte(0))
            assertEquals(true, buf.release(), "final release at refCount=1→0 frees handle")
        }
    }

    @Test
    fun release_after_zero_throws() {
        val payload = byteArrayOf(0x01)
        withDispatchHandle(payload) { ptr, len, handle ->
            val buf = DispatchDataIoBuf(ptr, len, handle)
            buf.release() // refCount 1 → 0, handle released
            assertFailsWith<IllegalStateException> { buf.release() }
        }
    }

    @Test
    fun retain_after_zero_throws() {
        val payload = byteArrayOf(0x02)
        withDispatchHandle(payload) { ptr, len, handle ->
            val buf = DispatchDataIoBuf(ptr, len, handle)
            buf.release()
            assertFailsWith<IllegalStateException> { buf.retain() }
        }
    }

    @Test
    fun close_releases_handle_and_is_idempotent() {
        val payload = byteArrayOf(0x03)
        withDispatchHandle(payload) { ptr, len, handle ->
            val buf = DispatchDataIoBuf(ptr, len, handle)
            buf.retain() // refCount 1 → 2, slice-like
            buf.close() // escape hatch: bypass refcount, free handle
            // Subsequent close is a no-op (no crash, no double-release).
            buf.close()
        }
    }

    @Test
    fun copyTo_copies_bytes_into_dest_iobuf() {
        val payload = "abcdef".encodeToByteArray()
        withDispatchHandle(payload) { ptr, len, handle ->
            val src = DispatchDataIoBuf(ptr, len, handle)
            val dst = DefaultAllocator.allocate(64)
            src.copyTo(dst, 4) // copies "abcd"
            assertEquals(4, src.readerIndex)
            assertEquals(4, dst.writerIndex)
            assertEquals('a'.code.toByte(), dst.getByte(0))
            assertEquals('d'.code.toByte(), dst.getByte(3))
            dst.release()
            src.release()
        }
    }

    @Test
    fun readByteArray_advances_reader_and_fills_dest_array() {
        val payload = "01234567".encodeToByteArray()
        withDispatchHandle(payload) { ptr, len, handle ->
            val src = DispatchDataIoBuf(ptr, len, handle)
            val dest = ByteArray(4)
            src.readerIndex = 2
            src.readByteArray(dest, 0, 4) // bytes 2..5 = "2345"
            assertEquals(6, src.readerIndex)
            assertEquals('2'.code.toByte(), dest[0])
            assertEquals('5'.code.toByte(), dest[3])
            src.release()
        }
    }

    // --- Harness ---

    private fun withDispatchHandle(
        payload: ByteArray,
        block: (ptr: kotlinx.cinterop.CPointer<ByteVar>, len: Int, handle: COpaquePointer) -> Unit,
    ) {
        payload.usePinned { pinned ->
            memScoped {
                val dummyBuf = allocArray<ByteVar>(64)
                val captured = CapturedHandle()
                val ref = StableRef.create(captured)
                try {
                    val srcHandle = checkNotNull(
                        keel_nw_test_make_data_single(pinned.addressOf(0), payload.size.toUInt()),
                    ) { "make_data_single returned null" }
                    try {
                        keel_nw_test_dispatch_handle(
                            srcHandle,
                            dummyBuf,
                            64u,
                            0,
                            captureCallback,
                            ref.asCPointer(),
                        )
                        val zcPtr = checkNotNull(captured.zcPtr) { "zc_ptr null after dispatch" }
                        val zcHandle = checkNotNull(captured.zcHandle) { "zc_handle null after dispatch" }
                        block(zcPtr, captured.len, zcHandle)
                    } finally {
                        // Outer source handle (separate retain). The
                        // inner zc_handle delivered to the callback is
                        // already +1 retained by the production path's
                        // __bridge_retained — block(...) is responsible
                        // for that one.
                        nwconnection.keel_nw_dispatch_data_release(srcHandle)
                    }
                } finally {
                    ref.dispose()
                }
            }
        }
    }

    private class CapturedHandle {
        var zcHandle: COpaquePointer? = null
        var zcPtr: kotlinx.cinterop.CPointer<ByteVar>? = null
        var len: Int = 0
    }

    companion object {
        private val captureCallback = staticCFunction {
                zcHandle: COpaquePointer?,
                zcPtr: COpaquePointer?,
                len: UInt,
                _: Int, _: Int,
                ctx: COpaquePointer?,
            ->
            val c = checkNotNull(ctx) { "ctx null" }.asStableRef<CapturedHandle>().get()
            c.zcHandle = zcHandle
            c.zcPtr = zcPtr?.reinterpret<ByteVar>()
            c.len = len.toInt()
        }
    }
}
