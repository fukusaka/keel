package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.defaultAllocator
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.nativeNullPtr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Seam-level unit tests for [RegisteredBufferStrategy] resolution in
 * [IoUringEventLoopGroup]:
 *
 * - `DYNAMIC` fails fast at group construction (not yet implemented) so a
 *   deployment that requested it never silently degrades to STATIC.
 * - `STATIC` on a kernel without `IORING_REGISTER_BUFFERS` resolves to the
 *   DISABLED behaviour (warn log + the null-object registry) and the group
 *   still starts.
 * - `DISABLED` short-circuits warmup + registration; every per-EventLoop
 *   slot holds [DisabledRegisteredBufferRegistry] after [IoUringEventLoopGroup.start].
 * - `STATIC` with a pooled allocator wires a [StaticRegisteredBufferRegistry]
 *   per EventLoop; with a non-pooled allocator the slot falls back to the
 *   null object (nothing to enumerate).
 *
 * Construction-time assertions run without starting any EventLoop pthread.
 * The post-`start` assertions spin up a real one-loop group (linuxTest runs
 * on a real kernel) and close it before the test exits — close() joins the
 * pthread, so no timeout is needed beyond the test framework's own bound.
 */
@OptIn(ExperimentalForeignApi::class)
class IoUringRegisteredBufferStrategySeamTest {

    private val logger = NoopLoggerFactory.logger("IoUringRegisteredBufferStrategySeamTest")

    @Test
    fun `DYNAMIC strategy fails fast at group construction`() {
        val ex = assertFailsWith<IllegalStateException> {
            IoUringEventLoopGroup(
                size = 1,
                logger = logger,
                allocator = DefaultAllocator,
                capabilities = IoUringCapabilities(registeredBuffers = true),
                registeredBufferStrategy = RegisteredBufferStrategy.DYNAMIC,
            )
        }
        assertTrue(
            ex.message!!.contains("DYNAMIC is not yet implemented"),
            "message should say DYNAMIC is unimplemented, got: ${ex.message}",
        )
    }

    @Test
    fun `bufferTableAt returns the null object before start`() {
        val group = IoUringEventLoopGroup(
            size = 1,
            logger = logger,
            allocator = DefaultAllocator,
            capabilities = IoUringCapabilities(registeredBuffers = true),
            registeredBufferStrategy = RegisteredBufferStrategy.STATIC,
        )
        try {
            val registry = group.bufferTableAt(0)
            assertIs<DisabledRegisteredBufferRegistry>(registry)
            assertFalse(registry.isActive)
            assertEquals(-1, registry.indexOf(DUMMY_PTR))
        } finally {
            group.close()
        }
    }

    @Test
    fun `DISABLED strategy populates the null object after start`() {
        val group = IoUringEventLoopGroup(
            size = 1,
            logger = logger,
            allocator = defaultAllocator(), // pooled — would register under STATIC
            capabilities = IoUringCapabilities(registeredBuffers = true),
            registeredBufferStrategy = RegisteredBufferStrategy.DISABLED,
        )
        try {
            group.start()
            // DISABLED short-circuits warmup + registration; the slot holds
            // the null object, not a StaticRegisteredBufferRegistry.
            assertIs<DisabledRegisteredBufferRegistry>(group.bufferTableAt(0))
        } finally {
            group.close()
        }
    }

    @Test
    fun `STATIC without kernel capability falls back to the null object after start`() {
        val group = IoUringEventLoopGroup(
            size = 1,
            logger = logger,
            allocator = defaultAllocator(), // pooled — would register if the cap were present
            capabilities = IoUringCapabilities(registeredBuffers = false),
            registeredBufferStrategy = RegisteredBufferStrategy.STATIC,
        )
        try {
            group.start()
            assertIs<DisabledRegisteredBufferRegistry>(group.bufferTableAt(0))
        } finally {
            group.close()
        }
    }

    @Test
    fun `STATIC with a pooled allocator wires a static registry after start`() {
        val group = IoUringEventLoopGroup(
            size = 1,
            logger = logger,
            allocator = defaultAllocator(), // SlabAllocator on native — pooled
            capabilities = IoUringCapabilities(registeredBuffers = true),
            registeredBufferStrategy = RegisteredBufferStrategy.STATIC,
        )
        try {
            group.start()
            val registry = group.bufferTableAt(0)
            assertIs<StaticRegisteredBufferRegistry>(registry)
            // Registration ran against the real kernel ring on the EL pthread.
            assertTrue(registry.isActive, "kernel registration must have succeeded on a real ring")
        } finally {
            group.close()
        }
    }

    @Test
    fun `STATIC with a non-pooled allocator falls back to the null object after start`() {
        val group = IoUringEventLoopGroup(
            size = 1,
            logger = logger,
            allocator = DefaultAllocator, // not a PooledAllocator — nothing to enumerate
            capabilities = IoUringCapabilities(registeredBuffers = true),
            registeredBufferStrategy = RegisteredBufferStrategy.STATIC,
        )
        try {
            group.start()
            assertIs<DisabledRegisteredBufferRegistry>(group.bufferTableAt(0))
        } finally {
            group.close()
        }
    }

    private companion object {
        // Synthetic non-null pointer for indexOf checks against the null
        // object — never dereferenced.
        @OptIn(ExperimentalForeignApi::class)
        private val DUMMY_PTR = interpretCPointer<ByteVar>(nativeNullPtr + 1L)!!
    }
}
