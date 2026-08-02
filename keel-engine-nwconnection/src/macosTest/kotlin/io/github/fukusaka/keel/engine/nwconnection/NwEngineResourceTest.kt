@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.SlabAllocator
import io.github.fukusaka.keel.buf.TrackingAllocator
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.close
import kotlin.concurrent.atomics.AtomicInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class NwEngineResourceTest {

    @Test
    fun `echo with TrackingAllocator has no buffer leak`() = runBlocking {
        val tracker = TrackingAllocator()
        val engine = NwEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        rawWrite(clientFd, "leak-check")
        val buf = DefaultAllocator.allocate(64)
        val n = withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.read(buf) }
        assertEquals(10, n)
        ch.write(buf)
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.flush() }

        val echo = rawRead(clientFd, 10)
        assertEquals("leak-check", echo)

        ch.close()
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.awaitClosed() }
        close(clientFd)
        server.close()
        engine.close()

        assertEquals(
            0,
            tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    @Test
    fun `connect with TrackingAllocator has no buffer leak`() = runBlocking {
        val tracker = TrackingAllocator()
        val engine = NwEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val client = engine.connect("127.0.0.1", port)
        val serverCh = server.accept()

        val writeBuf = DefaultAllocator.allocate(64)
        for (b in "test".encodeToByteArray()) writeBuf.writeByte(b)
        client.write(writeBuf)
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { client.flush() }

        val readBuf = DefaultAllocator.allocate(64)
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { serverCh.read(readBuf) }
        readBuf.release()

        client.close()
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { client.awaitClosed() }
        serverCh.close()
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { serverCh.awaitClosed() }
        server.close()
        engine.close()

        assertEquals(
            0,
            tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    @Test
    fun `engine close force-tears-down a still-live connection without leak`() = runBlocking {
        val tracker = TrackingAllocator()
        val engine = NwEngine(IoEngineConfig(allocator = tracker))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()
        // Drive one echo so the per-connection allocator child carves a buffer,
        // then leave the channel and client OPEN — the connection is still live.
        rawWrite(clientFd, "live-conn")
        val buf = DefaultAllocator.allocate(64)
        val n = withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.read(buf) }
        assertEquals(9, n)
        ch.write(buf)
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.flush() }

        // Close the engine with the connection still live. The per-connection
        // allocator child is untracked (createUntrackedChild), so the engine no
        // longer fans out to close it; trackConnection's finally must force the
        // teardown and join it. This returns without leaking the child's
        // buffers or hanging (a hang would trip the withTimeout).
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { engine.close() }
        close(clientFd)

        assertEquals(
            0,
            tracker.outstandingCount,
            "Buffer leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    @Test
    fun `per-connection allocators are untracked eagerly-drained and not re-closed by engine close`() = runBlocking {
        val counters = AllocatorCounters()
        val engine = NwEngine(IoEngineConfig(allocator = CountingAllocator(DefaultAllocator, counters)))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val n = 3
        repeat(n) {
            val clientFd = connectRawClient(port)
            val ch = server.accept()
            rawWrite(clientFd, "drain")
            val buf = DefaultAllocator.allocate(16)
            withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.read(buf) }
            buf.release()
            ch.close()
            withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.awaitClosed() }
            close(clientFd)
        }

        // Unbounded-growth guard: the engine takes exactly one tracked child (its
        // own), and every per-connection child is untracked — so the engine
        // allocator's children list never grows with the connection count. A
        // regression to createChild() would push trackedCreated to 1 + n.
        assertEquals(1, counters.trackedCreated.load(), "only the engine's own child is tracked")
        assertEquals(n, counters.untrackedCreated.load(), "each connection takes one untracked child")

        // Eager-drain guard: each per-connection allocator is closed at its own
        // connection teardown (before engine.close()), so its pooled chunks return
        // to the shared arena immediately instead of being held until engine close.
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) {
            while (counters.closes.load() < n) delay(5)
        }
        assertEquals(
            n,
            counters.closes.load(),
            "all per-connection children drain at their own teardown, before engine close",
        )

        server.close()
        engine.close()

        // Double-close guard: engine.close() closes exactly one more allocator (its
        // own child) and does NOT fan out to re-close the untracked per-connection
        // children — the double-close that crashed under CPU-constrained shutdown.
        assertEquals(
            n + 1,
            counters.closes.load(),
            "engine close must not re-close the untracked per-connection children",
        )
    }

    @Test
    fun `engine-direct DispatchDataIoBuf fires lifecycleListener on inbound zero-copy + release`() = runBlocking {
        // SlabAllocator is the Native-side PooledAllocator subclass; its
        // lifecycleListener parameter is the channel through which
        // BufferAllocator.lifecycleListener delivers the listener to the
        // engine's per-engine allocator via createChild propagation, then
        // NwIoTransport reads it for the engine-direct
        // DispatchDataIoBuf.wrapInbound factory (item 12 B2.5 step 3).
        val tracker = TrackingAllocator()
        val userAllocator = SlabAllocator(lifecycleListener = tracker)
        val engine = NwEngine(IoEngineConfig(allocator = userAllocator))
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val clientFd = connectRawClient(port)
        val ch = server.accept()

        // Drive an echo so the inbound zero-copy path produces a
        // DispatchDataIoBuf wrapped through wrapInbound, then a write that
        // exercises the allocator-allocated send buffer.
        rawWrite(clientFd, "listener-mode")
        val buf = DefaultAllocator.allocate(64)
        val n = withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.read(buf) }
        assertEquals(13, n)
        ch.write(buf)
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.flush() }

        val echo = rawRead(clientFd, 13)
        assertEquals("listener-mode", echo)

        ch.close()
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { ch.awaitClosed() }
        close(clientFd)
        server.close()
        engine.close()
        userAllocator.close()

        assertTrue(
            tracker.allocateCount > 0,
            "lifecycle listener must observe at least one engine-direct allocate",
        )
        assertEquals(
            0,
            tracker.outstandingCount,
            "Listener-mode leak: allocated=${tracker.allocateCount}, released=${tracker.releaseCount}",
        )
    }

    @OptIn(kotlin.native.runtime.NativeRuntimeApi::class, ExperimentalStdlibApi::class)
    @Test
    fun `GC heap size does not grow after repeated echo cycles`() = runBlocking {
        val engine = NwEngine()
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        // Warm up
        val clientFd = connectRawClient(port)
        val ch = server.accept()
        rawWrite(clientFd, "warmup")
        val warmBuf = DefaultAllocator.allocate(64)
        withTimeout(GC_ECHO_OP_TIMEOUT_MS) { ch.read(warmBuf) }
        warmBuf.release()

        // Baseline GC
        kotlin.native.runtime.GC.collect()
        val baselineInfo = kotlin.native.runtime.GC.lastGCInfo
        val baselineHeap = baselineInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: 0L

        // Run 50 echo cycles (fewer than kqueue/epoll due to dispatch callback latency).
        // Prior to [NwConnectionQueueDispatcher] this loop stalled at cycle 13 on
        // GitHub Actions `macos-latest` because `ioDispatcher = Dispatchers.Default`
        // raced with NWConnection's dispatch-queue callbacks against
        // `SuspendBridgeHandler`'s single-thread invariant (PR #308 workaround).
        // With `ioDispatcher` now pointing at `connQueue`, both `onRead` and
        // `bridge.read` run serialised on the same queue and the race is
        // structurally eliminated.
        repeat(50) {
            rawWrite(clientFd, "test")
            val buf = DefaultAllocator.allocate(64)
            val n = withTimeout(GC_ECHO_OP_TIMEOUT_MS) { ch.read(buf) }
            if (n > 0) {
                ch.write(buf)
                withTimeout(GC_ECHO_OP_TIMEOUT_MS) { ch.flush() }
            }
        }
        rawRead(clientFd, 200) // drain echoed data

        // Post-test GC
        kotlin.native.runtime.GC.collect()
        val afterInfo = kotlin.native.runtime.GC.lastGCInfo
        val afterHeap = afterInfo?.memoryUsageAfter?.get("heap")?.totalObjectsSizeBytes ?: 0L

        // Heap growth tolerance: fixed 512KB absolute increase.
        // NWConnection creates per-callback StableRef + CallbackContext objects
        // which are disposed in callbacks, but GC internal state may retain
        // metadata. 512KB absorbs this variance while catching real leaks.
        val heapGrowthTolerance = 512L * 1024
        val maxAllowed = baselineHeap + heapGrowthTolerance
        assertTrue(
            afterHeap <= maxAllowed,
            "Heap grew from $baselineHeap to $afterHeap bytes after 50 echo cycles " +
                "(tolerance: ${heapGrowthTolerance / 1024}KB). Possible memory leak.",
        )

        ch.close()
        close(clientFd)
        server.close()
        engine.close()
    }
}

/**
 * Shared counters for [CountingAllocator], mutated from GCD teardown callbacks
 * (per-connection `allocator.close()`) and read from the test coroutine, so the
 * counts are [AtomicInt]s.
 */
private class AllocatorCounters {
    val trackedCreated = AtomicInt(0)
    val untrackedCreated = AtomicInt(0)
    val closes = AtomicInt(0)
}

/**
 * A [BufferAllocator] decorator that counts `createChild` (tracked) vs
 * `createUntrackedChild` (untracked) and `close` calls across the whole child
 * tree into a shared [AllocatorCounters]. Lets a test assert that every
 * per-connection child is untracked (bounded children list), drained at its own
 * teardown (eager drain), and closed exactly once (no engine fan-out re-close).
 */
private class CountingAllocator(
    private val delegate: BufferAllocator,
    private val counters: AllocatorCounters,
) : BufferAllocator by delegate {
    override fun createChild(): BufferAllocator {
        counters.trackedCreated.fetchAndAdd(1)
        return CountingAllocator(delegate.createChild(), counters)
    }

    override fun createUntrackedChild(): BufferAllocator {
        counters.untrackedCreated.fetchAndAdd(1)
        return CountingAllocator(delegate.createUntrackedChild(), counters)
    }

    override fun close() {
        counters.closes.fetchAndAdd(1)
        delegate.close()
    }
}
