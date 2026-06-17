package io.github.fukusaka.keel.sample.observability

import io.github.fukusaka.keel.buf.BufferAllocatorLifecycleListener
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.PooledDirectAllocator
import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.netty.NettyEngine
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong

/**
 * Visual-verification sample for pluggability item 12 B2.5 step 2:
 * `BufferAllocator.lifecycleListener` propagation into NettyEngine.
 *
 * Wires an [AtomicTracker] (a minimal thread-safe
 * [BufferAllocatorLifecycleListener]) into a [PooledDirectAllocator]'s
 * `lifecycleListener` parameter, then passes that allocator into a
 * [NettyEngine] via [IoEngineConfig]. Drives a sustained echo workload
 * through the engine: the server-side handler reads inbound bytes
 * (engine-direct `NettyByteBufIoBuf.wrapInbound`) and writes them back
 * (engine-direct `NettyByteBufAllocator.allocate()`). Both paths fire
 * the listener through the new `BufferAllocator.lifecycleListener`
 * getter the engine reads from `config.allocator.lifecycleListener`,
 * so the sample prints `allocateCount` / `releaseCount` /
 * `outstandingCount` climbing as the workload progresses — concrete
 * evidence the listener observes every engine-direct buffer lifecycle
 * event.
 *
 * **Why a hand-rolled [AtomicTracker] rather than [io.github.fukusaka.keel.buf.TrackingAllocator]
 * in listener mode.** `TrackingAllocator` listener mode uses plain
 * `++` on its counters and is documented as not-thread-safe (intended
 * for single-threaded test execution). `NettyEngine` defaults to
 * Netty's worker count (`Runtime.availableProcessors() * 2`) so
 * `onAllocated` / `onReleased` callbacks run on whichever worker
 * EventLoop owns the connection — and many short-lived connections
 * spread across EventLoops during this sample, racing on the same
 * counters. Two `AtomicLong`s keep the counts correct without needing
 * a synchronisation layer in `keel-io`.
 *
 * Stop with Ctrl-C; the engine and the server are closed in the
 * shutdown hook so the listener counters end balanced
 * (`outstandingCount == 0`).
 *
 * Run with:
 *
 * ```
 * ./gradlew -Pbenchmark :sample:runNettyListenerSample
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
    val userAllocator = PooledDirectAllocator(lifecycleListener = tracker)
    val engine = NettyEngine(IoEngineConfig(allocator = userAllocator))
    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking {
                engine.close()
                userAllocator.close()
                printSnapshot(tracker, iter = -1, finalLine = true)
            }
        },
    )

    println("keel NettyEngine listener-wiring sample (item 12 B2.5 step 2).")
    println("Echoing loopback traffic through NettyEngine; printing AtomicTracker counters every $SNAPSHOT_INTERVAL_MILLIS ms.")
    runBlocking {
        val server = engine.bind("127.0.0.1", 0)
        val port = (server.localAddress as InetSocketAddress).port

        // Server-side accept loop runs concurrently with the client driver.
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

        var lastSnapshotAt = System.currentTimeMillis()
        var i = 0
        while (i < iters) {
            val socket = Socket(InetAddress.getLoopbackAddress(), port).apply { soTimeout = 1_000 }
            socket.use { s ->
                val payload = "iter-$i".toByteArray()
                s.getOutputStream().write(payload)
                s.getOutputStream().flush()
                val echo = ByteArray(payload.size)
                var total = 0
                while (total < payload.size) {
                    val n = s.getInputStream().read(echo, total, payload.size - total)
                    if (n <= 0) break
                    total += n
                }
            }
            i++
            val now = System.currentTimeMillis()
            if (now - lastSnapshotAt >= SNAPSHOT_INTERVAL_MILLIS) {
                printSnapshot(tracker, iter = i)
                lastSnapshotAt = now
            }
            delay(PAUSE_MILLIS)
        }
        acceptJob.cancel()
    }
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
 * [AtomicLong]s. See the class-level KDoc for why `TrackingAllocator`
 * listener mode is not used directly.
 */
private class AtomicTracker : BufferAllocatorLifecycleListener {
    private val allocated = AtomicLong(0)
    private val released = AtomicLong(0)
    val allocateCount: Long get() = allocated.get()
    val releaseCount: Long get() = released.get()
    val outstandingCount: Long get() = allocateCount - releaseCount
    override fun onAllocated(buf: IoBuf) {
        allocated.incrementAndGet()
    }
    override fun onReleased(buf: IoBuf) {
        released.incrementAndGet()
    }
}

private const val SNAPSHOT_INTERVAL_MILLIS = 3_000L
private const val PAUSE_MILLIS = 20L
private const val IO_TIMEOUT_MILLIS = 1_000L
