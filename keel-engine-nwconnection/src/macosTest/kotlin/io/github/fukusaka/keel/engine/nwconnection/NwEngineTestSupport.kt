package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.native.posix.PosixRawClient
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.posix.getpid
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Shared helpers + constants for the categorised `NwEngine*Test` files.
 *
 * Test category split:
 *
 * | file | scope |
 * |---|---|
 * | [NwEngineLifecycleTest] | engine create/close, server bind/close, error paths, double close, UDS variants |
 * | [NwEngineReadWriteTest] | echo, multi-write, half-close, `asSuspendSource` / `asSuspendSink` |
 * | [NwEngineConnectTest]   | client `connect()` flows |
 * | [NwEngineConcurrencyTest] | concurrent reads, FIFO accept queue, multi-client accept, cancellation |
 * | [NwEngineResourceTest]  | `TrackingAllocator` leak detection + GC heap stability |
 *
 * Mirrors the same category split applied to the other engine test
 * suites (kqueue / epoll / io_uring / nio / netty / nwconnection /
 * nodejs). This file is the concrete realisation for the NWConnection engine.
 */

// Per-operation hang-detection timeout for tests that exercise
// accept / read / job completion. Short enough to surface a real
// hang (normal latency on loopback is <50ms locally, <500ms on CI)
// but long enough not to flake on CI runners under load.
internal const val IO_OP_TIMEOUT_MS = 5_000L
internal const val IO_OP_SHORT_TIMEOUT_MS = 3_000L

/** Whole-test envelope, the same wrapper the nio and netty suites use. */
internal val TEST_TIMEOUT = 15.seconds

internal fun runTest(block: suspend CoroutineScope.() -> Unit) =
    runBlocking { withTimeout(TEST_TIMEOUT, block) }

/** Loopback host for test servers — a wildcard listener does not own its port (see the nio suite's note). */
internal const val LOOPBACK_HOST: String = "127.0.0.1"

// Per-operation timeout used specifically by the GC heap echo-cycle
// test. Separate constant so the heap-echo loop can be tuned
// independently from the other NWConnection tests if its
// retention-sensitive workload needs a different bound.
internal const val GC_ECHO_OP_TIMEOUT_MS = 3_000L

private var udsPathSeq = 0

internal fun uniqueUdsPath(): String {
    val pid = getpid()
    val seq = udsPathSeq++
    return "/tmp/keel-nw-uds-$pid-$seq.sock"
}

internal fun connectRawClient(port: Int): Int = PosixRawClient.rawConnect(port)

internal fun rawWrite(fd: Int, data: String): Unit = PosixRawClient.rawWrite(fd, data)

internal fun rawRead(fd: Int, size: Int): String = PosixRawClient.rawRead(fd, size)

// --- A client whose peer finished (shared by the once-per-transport tests) ---

/**
 * A client connection whose peer has finished. [transport] is the client's,
 * [channel] the client's channel handle, and [reports] the number of end
 * reports the replaced listener has heard — one by the time the body runs.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class PeerFinFixture(
    val channel: PipelinedChannel,
    val transport: IoTransport,
    private val counter: AtomicInt,
) {
    val reports: Int get() = counter.load()

    /** Runs [block] on the transport's own dispatcher — the read-arming setters are EventLoop-confined. */
    suspend fun onLoop(block: () -> Unit) = withContext(transport.ioDispatcher) { block() }

    /** Polls until the transport is closed or [budgetMillis] passes; returns whether it closed. */
    suspend fun awaitReclaimed(budgetMillis: Long): Boolean {
        val deadline = TimeSource.Monotonic.markNow() + budgetMillis.milliseconds
        while (transport.isOpen && deadline.hasNotPassedNow()) delay(RECLAIM_POLL_MS)
        return !transport.isOpen
    }
}

/**
 * Binds a loopback server on a fresh engine built from [config], connects a
 * client, replaces the client transport's listener with a counter, enables
 * reads on the loop (what arms the read-idle clock), and has the peer close
 * — a FIN the counter has heard exactly once when [body] runs. Owns the
 * teardown of the client, the server and the engine.
 */
@OptIn(ExperimentalAtomicApi::class)
internal suspend fun withPeerFin(config: IoEngineConfig, body: suspend (PeerFinFixture) -> Unit) {
    val engine = NwEngine(config)
    val server = engine.bind(LOOPBACK_HOST, 0)
    val port = (server.localAddress as InetSocketAddress).port
    val client = engine.connect(LOOPBACK_HOST, port)
    val serverCh = server.accept()
    try {
        val transport = (client as AbstractPipelinedChannel).transport
        val counter = AtomicInt(0)
        val first = CompletableDeferred<Unit>()
        transport.onReadClosed = {
            counter.fetchAndAdd(1)
            first.complete(Unit)
        }
        withContext(transport.ioDispatcher) { transport.readEnabled = true }
        serverCh.close()
        withTimeout(IO_OP_SHORT_TIMEOUT_MS) { first.await() }
        body(PeerFinFixture(client, transport, counter))
    } finally {
        client.close()
        server.close()
        engine.close()
    }
}

private const val RECLAIM_POLL_MS = 10L
