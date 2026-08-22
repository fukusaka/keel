package io.github.fukusaka.keel.buf

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * What an engine learns about an allocator before it serves anything.
 *
 * A Native engine reaches buffer memory through an unchecked cast, because it
 * runs on every read. An allocator whose buffers cannot take that cast fails at
 * the first byte moved — inside a readiness dispatch, once per connection, as a
 * `ClassCastException` in a log. The check these cases cover turns that into one
 * refusal at the point the allocator was chosen.
 *
 * Three things about *how* it asks are pinned here: it asks at the size a read
 * will use, it gives back what it took on both paths, and a release that fails
 * on the way out does not take the place of the refusal.
 */
class NativePointerAccessCheckTest {

    @Test
    fun `an allocator whose buffers are native is accepted`() {
        DefaultAllocator.requireNativePointerAccess("TestEngine", READ_BUFFER_SIZE)
    }

    @Test
    fun `an allocator whose buffers are not native is refused by name`() {
        val refused = assertFailsWith<IllegalArgumentException> {
            PointerlessAllocator.requireNativePointerAccess("TestEngine", READ_BUFFER_SIZE)
        }

        val message = checkNotNull(refused.message)
        assertTrue(message.contains("TestEngine"), "the engine refusing must be named, got: $message")
        assertTrue(
            message.contains("PointerlessAllocator"),
            "and the allocator the user configured, got: $message",
        )
        assertTrue(
            message.contains("PointerlessIoBuf"),
            "and what it allocates, since that is what the cast fails on, got: $message",
        )
    }

    @Test
    fun `the probe asks at the size the engine will read into`() {
        // A pooled allocator can build a small buffer and a large one through
        // different seams, so a probe at some other size attests the wrong one.
        val recording = RecordingAllocator()

        recording.requireNativePointerAccess("TestEngine", READ_BUFFER_SIZE)

        assertEquals(READ_BUFFER_SIZE, recording.lastRequestedCapacity)
    }

    @Test
    fun `the probe releases what it allocated`() {
        val recording = RecordingAllocator()

        recording.requireNativePointerAccess("TestEngine", READ_BUFFER_SIZE)

        assertTrue(recording.allocated > 0, "the check has to allocate to learn anything")
        assertEquals(
            recording.allocated,
            recording.released,
            "and must give every one back: allocated ${recording.allocated}, released ${recording.released}",
        )
    }

    @Test
    fun `the probe releases what it allocated when it refuses`() {
        // The path that throws is the one that would go unnoticed: a leak here
        // only ever happens to someone whose configuration is already wrong.
        val recording = RecordingAllocator(pointerless = true)

        assertFailsWith<IllegalArgumentException> {
            recording.requireNativePointerAccess("TestEngine", READ_BUFFER_SIZE)
        }

        assertTrue(recording.allocated > 0, "the check has to allocate to learn anything")
        assertEquals(
            recording.allocated,
            recording.released,
            "and must give it back even when refusing: allocated ${recording.allocated}, " +
                "released ${recording.released}",
        )
    }

    @Test
    fun `an allocator that will not allocate raises its own failure`() {
        // Nothing was handed out, so there is nothing to refuse. Dressing this
        // as a refusal would say the buffers are wrong when none were made.
        val recording = RecordingAllocator(failAllocate = true)

        val failed = assertFailsWith<IllegalStateException> {
            recording.requireNativePointerAccess("TestEngine", READ_BUFFER_SIZE)
        }

        assertEquals("allocate refused", failed.message)
        assertEquals(0, recording.allocated, "and it never got as far as handing one out")
    }

    @Test
    fun `a failed release on an accepted allocator is what the caller hears`() {
        // Nothing is wrong with the buffers, so there is no refusal to carry it:
        // the release failure is the only thing left to say.
        val recording = RecordingAllocator(failRelease = true)

        val failed = assertFailsWith<IllegalStateException> {
            recording.requireNativePointerAccess("TestEngine", READ_BUFFER_SIZE)
        }

        assertEquals("release refused", failed.message)
    }

    @Test
    fun `a failed release does not replace the refusal`() {
        // The refusal names the mistake a user made; a release that throws on
        // top of it is news, but not instead of that.
        val recording = RecordingAllocator(pointerless = true, failRelease = true)

        val refused = assertFailsWith<IllegalArgumentException> {
            recording.requireNativePointerAccess("TestEngine", READ_BUFFER_SIZE)
        }

        assertTrue(
            refused.suppressedExceptions.any { it.message == "release refused" },
            "and carries what went wrong on the way out, got: ${refused.suppressedExceptions}",
        )
    }

    /** Everything it hands out fails the pointer cast. */
    private object PointerlessAllocator : BufferAllocator by DefaultAllocator {
        override fun allocate(capacity: Int): IoBuf = PointerlessIoBuf(DefaultAllocator.allocate(capacity))
    }

    /** An [IoBuf] that does not implement [NativePointerAccess]. */
    private class PointerlessIoBuf(delegate: IoBuf) : IoBuf by delegate

    /** Records what the probe asks for and what it gives back. */
    private class RecordingAllocator(
        private val pointerless: Boolean = false,
        private val failRelease: Boolean = false,
        private val failAllocate: Boolean = false,
    ) : BufferAllocator by DefaultAllocator {
        var allocated = 0
            private set
        var released = 0
            private set
        var lastRequestedCapacity = -1
            private set

        override fun allocate(capacity: Int): IoBuf {
            if (failAllocate) throw IllegalStateException("allocate refused")
            allocated++
            lastRequestedCapacity = capacity
            val delegate = DefaultAllocator.allocate(capacity)
            return if (pointerless) CountedPointerless(delegate) else Counted(delegate)
        }

        private fun countRelease(delegate: IoBuf): Boolean {
            released++
            val gaveBack = delegate.release()
            if (failRelease) throw IllegalStateException("release refused")
            return gaveBack
        }

        @OptIn(ExperimentalForeignApi::class, UnsafeIoBufApi::class)
        private inner class Counted(private val delegate: IoBuf) : IoBuf by delegate, NativePointerAccess {
            @UnsafeIoBufApi
            override val unsafePointer: CPointer<ByteVar> = delegate.unsafePointer

            override fun release(): Boolean = countRelease(delegate)
        }

        /** The same counting, on a buffer the cast fails on. */
        private inner class CountedPointerless(private val delegate: IoBuf) : IoBuf by delegate {
            override fun release(): Boolean = countRelease(delegate)
        }
    }

    private companion object {
        /** A plausible engine read size, and not some constant of the probe's own. */
        const val READ_BUFFER_SIZE = 16 * 1024
    }
}
