package io.github.fukusaka.keel.engine.nodejs

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.pipeline.AbstractPipelinedChannel
import io.github.fukusaka.keel.pipeline.IoTransport
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Shared helpers + constants for the categorised `NodeEngine*Test` files.
 *
 * Test category split:
 *
 * | file | scope |
 * |---|---|
 * | [NodeEngineLifecycleTest] | engine create/close, server bind/close, error paths, double close, UDS variants |
 * | [NodeEngineReadWriteTest] | echo, multi-write, half-close, `asSuspendSource` / `asSuspendSink` |
 * | [NodeEngineConnectTest]   | client `connect()` flows |
 * | [NodeEngineConcurrencyTest] | concurrent accept FIFO queue |
 *
 * Mirrors the same category split applied to the other engine test
 * suites (kqueue / epoll / io_uring / nio / netty / nwconnection).
 */

// Per-operation hang-detection timeout for tests that await a loop event
// (a FIN, a close). Short enough to surface a real hang, long enough not to
// flake on CI runners under load.
internal const val IO_OP_SHORT_TIMEOUT_MS = 3_000L

/** Loopback host for test servers — a wildcard listener does not own its port (see the nio suite's note). */
internal const val LOOPBACK_HOST: String = "127.0.0.1"

private var udsSeq = 0

internal fun uniqueUdsPath(): String {
    val seq = udsSeq++
    // Node's process.pid is available in Node.js runtime.
    val pid: Int = js("process.pid") as Int
    return "/tmp/keel-nodejs-uds-$pid-$seq.sock"
}

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
    val engine = NodeEngine(config)
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
