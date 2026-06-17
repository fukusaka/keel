@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package io.github.fukusaka.keel.sample.observability

import io.github.fukusaka.keel.buf.BufferAllocatorLifecycleListener
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.SlabAllocator
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.nwconnection.NwEngine
import kotlin.concurrent.atomics.AtomicLong
import kotlin.time.TimeSource
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Visual-verification sample for pluggability item 12 B2.5 step 3:
 * `BufferAllocator.lifecycleListener` propagation into `NwEngine`.
 *
 * Native counterpart of `sample/observability/NettyEngineListenerSample`
 * (item 12 B2.5 step 2). Wires an [AtomicTracker] (a minimal
 * thread-safe [BufferAllocatorLifecycleListener]) into a
 * [SlabAllocator]'s `lifecycleListener` parameter ([SlabAllocator] is
 * the Native-side [io.github.fukusaka.keel.buf.PooledAllocator]
 * subclass — `PooledDirectAllocator` is JVM-only), passes that
 * allocator into an [NwEngine] via [IoEngineConfig], then drives a
 * sustained loopback echo through `engine.bind()` / `engine.connect()`
 * — both client and server sides run on the same engine.
 *
 * Each round-trip drives:
 *  - the inbound zero-copy path that produces a `DispatchDataIoBuf`
 *    via `DispatchDataIoBuf.wrapInbound(..., allocator.lifecycleListener)`
 *    on the server side
 *  - allocator-allocated send buffers via the per-engine
 *    `SlabAllocator` on both sides
 *
 * Both paths fire the listener through the new
 * `BufferAllocator.lifecycleListener` getter the engine reads via
 * `config.allocator.createChild()`, so the sample prints
 * `allocateCount` / `releaseCount` / `outstandingCount` climbing as
 * the workload progresses.
 *
 * **Why a hand-rolled [AtomicTracker] rather than
 * [io.github.fukusaka.keel.buf.TrackingAllocator] in listener mode.**
 * `TrackingAllocator` listener mode is documented as not-thread-safe
 * (plain `++` on its counters). NWConnection's per-connection
 * dispatch queue is a GCD serial queue that serialises blocks but does
 * NOT pin them to one OS thread — listener callbacks can run on
 * different worker threads. Two `AtomicLong`s keep the counts correct
 * without needing a synchronisation layer in `keel-io`. (Same shape as
 * the Netty sample's `AtomicTracker`.)
 *
 * Run with:
 *
 * ```
 * ./gradlew -Pbenchmark :sample:linkNwListenerSampleReleaseExecutableMacosArm64
 * ./sample/build/bin/macosArm64/NwListenerSampleReleaseExecutable/NwListenerSample.kexe --iters=2000
 * ```
 *
 * Optional `--iters=N` bounds the loop (default `Int.MAX_VALUE`).
 */
@OptIn(DelicateCoroutinesApi::class)
fun main(args: Array<String>) {
    val iters = args.firstOrNull { it.startsWith("--iters=") }
        ?.substringAfter("=")?.toIntOrNull()
        ?: Int.MAX_VALUE

    val tracker = AtomicTracker()
    val userAllocator = SlabAllocator(lifecycleListener = tracker)
    val engine = NwEngine(IoEngineConfig(allocator = userAllocator))

    println("keel NwEngine listener-wiring sample (item 12 B2.5 step 3).")
    println("Echoing loopback traffic through NwEngine; printing AtomicTracker counters every $SNAPSHOT_INTERVAL_MILLIS ms.")

    runBlocking {
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        val acceptJob = GlobalScope.launch {
            while (true) {
                val ch = try {
                    server.accept()
                } catch (t: Throwable) {
                    break
                }
                GlobalScope.launch { echo(ch) }
            }
        }

        val tick = TimeSource.Monotonic
        var lastSnapshotAt = tick.markNow()
        var i = 0
        while (i < iters) {
            val client = engine.connect("127.0.0.1", port)
            try {
                val payload = "iter-$i".encodeToByteArray()
                val writeBuf = DefaultAllocator.allocate(64)
                for (b in payload) writeBuf.writeByte(b)
                client.write(writeBuf)
                withTimeout(IO_TIMEOUT_MILLIS) { client.flush() }
                val readBuf = DefaultAllocator.allocate(64)
                withTimeout(IO_TIMEOUT_MILLIS) { client.read(readBuf) }
                readBuf.release()
            } finally {
                client.close()
                withTimeout(IO_TIMEOUT_MILLIS) { client.awaitClosed() }
            }
            i++
            val now = tick.markNow()
            if ((now - lastSnapshotAt).inWholeMilliseconds >= SNAPSHOT_INTERVAL_MILLIS) {
                printSnapshot(tracker, iter = i)
                lastSnapshotAt = now
            }
            delay(PAUSE_MILLIS)
        }
        acceptJob.cancel()
        server.close()
        engine.close()
    }
    userAllocator.close()
    printSnapshot(tracker, iter = iters, finalLine = true)
}

private suspend fun echo(ch: Channel) {
    try {
        while (true) {
            val buf = DefaultAllocator.allocate(64)
            val n = withTimeout(IO_TIMEOUT_MILLIS) { ch.read(buf) }
            if (n <= 0) {
                buf.release()
                break
            }
            ch.write(buf)
            withTimeout(IO_TIMEOUT_MILLIS) { ch.flush() }
        }
    } catch (_: Throwable) {
        // peer closed / cancelled
    } finally {
        ch.close()
    }
}

private fun printSnapshot(tracker: AtomicTracker, iter: Int, finalLine: Boolean = false) {
    val prefix = if (finalLine) "[final]" else "[iter=$iter]"
    println(
        "$prefix alloc=${tracker.allocateCount} rel=${tracker.releaseCount} outstanding=${tracker.outstandingCount}",
    )
}

/**
 * Minimal thread-safe [BufferAllocatorLifecycleListener] using two
 * [AtomicLong]s. See the file-level KDoc for why
 * `TrackingAllocator` listener mode is not used directly.
 */
private class AtomicTracker : BufferAllocatorLifecycleListener {
    private val allocated = AtomicLong(0)
    private val released = AtomicLong(0)
    val allocateCount: Long get() = allocated.load()
    val releaseCount: Long get() = released.load()
    val outstandingCount: Long get() = allocateCount - releaseCount
    override fun onAllocated(buf: IoBuf) {
        allocated.fetchAndAdd(1)
    }
    override fun onReleased(buf: IoBuf) {
        released.fetchAndAdd(1)
    }
}

private const val SNAPSHOT_INTERVAL_MILLIS = 3_000L
private const val PAUSE_MILLIS = 20L
private const val IO_TIMEOUT_MILLIS = 1_000L
