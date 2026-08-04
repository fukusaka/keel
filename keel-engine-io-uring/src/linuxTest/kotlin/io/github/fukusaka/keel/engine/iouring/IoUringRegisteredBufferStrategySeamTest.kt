package io.github.fukusaka.keel.engine.iouring

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.defaultAllocator
import io.github.fukusaka.keel.buf.unsafePointer
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
@OptIn(ExperimentalForeignApi::class, UnsafeIoBufApi::class)
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

    // --- SEND_ZC dispatch counters (fixed vs regular split) ---

    @Test
    fun `regular SEND_ZC dispatch increments sendZcRegularCount`() {
        val fake = FakeIoUringRing()
        val el = IoUringEventLoop(
            NoopLoggerFactory.logger("counter-test"),
            syscallOps = FakeIoUringSyscallOps(),
            ioUringRing = fake,
        )
        val transport = IoUringIoTransport(
            fd = 999,
            eventLoop = el,
            capabilities = IoUringCapabilities(sendZc = true),
            writeModeSelector = IoModeSelectors.SEND_ZC,
            allocator = DefaultAllocator,
            // Disabled registry → every dispatch takes the regular branch.
            registeredBufferTable = DisabledRegisteredBufferRegistry,
        )
        try {
            val buf = DefaultAllocator.allocate(16)
            for (i in 0 until 16) buf.writeByte(i.toByte())
            transport.write(buf)
            transport.flush()

            assertEquals(0, el.sendZcFixedCount, "no registered buffers — fixed count stays 0")
            assertEquals(1, el.sendZcRegularCount, "the dispatch must count as a regular SEND_ZC")
        } finally {
            el.close()
            fake.dispose()
        }
    }

    @Test
    fun `fixed SEND_ZC dispatch increments sendZcFixedCount`() {
        val fake = FakeIoUringRing()
        val logger = NoopLoggerFactory.logger("counter-test")
        val el = IoUringEventLoop(logger, syscallOps = FakeIoUringSyscallOps(), ioUringRing = fake)
        // Register the exact buffer the test writes, so indexOf hits.
        val buf = DefaultAllocator.allocate(16)
        for (i in 0 until 16) buf.writeByte(i.toByte())
        val registry = StaticRegisteredBufferRegistry(
            el,
            listOf(buf.unsafePointer to 16),
            logger,
            FakeIoUringRegisteredBufferOps(),
        )
        registry.initOnEventLoop()
        val transport = IoUringIoTransport(
            fd = 999,
            eventLoop = el,
            capabilities = IoUringCapabilities(sendZc = true),
            writeModeSelector = IoModeSelectors.SEND_ZC,
            allocator = DefaultAllocator,
            registeredBufferTable = registry,
        )
        try {
            // Inside the try: a failure here used to skip registry.close(), el.close()
            // and the disposal below, so the one assertion that can fail before the
            // cleanup is installed was also the one that defeated it.
            assertTrue(registry.isActive, "fake-backed registration succeeds")
            transport.write(buf)
            transport.flush()

            assertEquals(1, el.sendZcFixedCount, "the dispatch must count as SEND_ZC_FIXED")
            assertEquals(0, el.sendZcRegularCount, "registered buffer — regular count stays 0")
        } finally {
            registry.close()
            el.close()
            fake.dispose()
        }
    }

    private companion object {
        // Synthetic non-null pointer for indexOf checks against the null
        // object — never dereferenced.
        @OptIn(ExperimentalForeignApi::class)
        private val DUMMY_PTR = interpretCPointer<ByteVar>(nativeNullPtr + 1L)!!
    }
}
