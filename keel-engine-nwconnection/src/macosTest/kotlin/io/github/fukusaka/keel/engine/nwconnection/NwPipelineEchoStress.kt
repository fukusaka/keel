package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-thread funnel regression stress for [NwEngine] (NWConnection / GCD).
 *
 * **Purpose**: guards the I/O ownership invariant — every channel operation
 * must reach the channel's owning dispatcher via the cross-thread funnel. Each
 * virtual user holds a keel [Channel] obtained from [NwEngine.connect] and
 * drives `write` / `read` from a [Dispatchers.Default] coroutine that is not the
 * channel's `ioDispatcher`; every call therefore round-trips through
 * `runOnEventLoop` onto the connection's GCD queue. An fd-routing regression
 * surfaces as an echo mismatch (`vu=<i>` payload tagged so any misrouting fails
 * `assertEquals` immediately).
 *
 * **Why no `threads = 4` here (unlike the POSIX / JVM engine stresses)**:
 * [NwEngine] is GCD-based and does not use the `IoEngineConfig.threads` option
 * as a worker-EventLoop count. Each NWConnection owns its own per-connection
 * `dispatch_queue` ([NwConnectionQueueDispatcher]) and GCD migrates that queue's
 * work across its internal worker pthread pool. Confinement is therefore decided
 * by an on-queue marker ([NwQueueConfinement] via `dispatch_get_specific`), not a
 * thread id. The concurrency dimension this stress exercises is the 50
 * per-connection queues running concurrently over the GCD pthread pool, plus the
 * cross-queue funnel — varying `threads` would change nothing.
 *
 * **Concurrent-allocate coverage**: each VU allocates its `write` / `read`
 * buffers from `ch.allocator` on its [Dispatchers.Default] coroutine while the
 * engine allocates read buffers for the same channel on the GCD queue — two
 * threads allocating on one per-connection allocator. This end-to-end exercises
 * the shared Native pooled-allocator concurrent-allocate path (the per-call drain
 * scratch fix, #836) under the NW confinement model, complementing the
 * allocator-level churn unit test.
 *
 * **Why a keel-self client (not a raw socket)**: the funnel is a property of
 * keel's Channel API, so the client must call into the API to exercise it. A
 * raw socket bypasses the API entirely and verifies only kernel-level echo, not
 * the invariant. `engine.connect` also keeps the client suspend-based, so 50
 * concurrent VUs do not starve the fixed-size Native [Dispatchers.Default] pool.
 *
 * **Gating**: every `@Test` returns early unless the `KEEL_STRESS` environment
 * variable is set (`quick` runs the quick scenario only, `full` runs both). The
 * CI quick-gate workflow sets `KEEL_STRESS=quick` on engine PRs; the engine's
 * stress `workflow_dispatch` yml sets `KEEL_STRESS=full`.
 */
@OptIn(ExperimentalForeignApi::class)
class NwPipelineEchoStress {

    /** Writes every inbound IoBuf straight back to the peer (transfers ownership downstream). */
    private class EchoHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            ctx.propagateWriteAndFlush(msg)
        }
    }

    /**
     * Quick scenario: [VU] × [QUICK_ROUNDS] echo round-trips.
     * Runs on engine PR gate (`KEEL_STRESS=quick`), budget [QUICK_BUDGET].
     */
    @Test
    fun quick() = runBlocking {
        if (stressMode() !in setOf("quick", "full")) return@runBlocking
        withTimeout(QUICK_BUDGET) {
            runEchoStress(rounds = QUICK_ROUNDS)
        }
    }

    /**
     * Full scenario: [VU] × [FULL_ROUNDS] echo round-trips.
     * Runs on `workflow_dispatch` only (`KEEL_STRESS=full`), budget [FULL_BUDGET].
     */
    @Test
    fun full() = runBlocking {
        if (stressMode() != "full") return@runBlocking
        withTimeout(FULL_BUDGET) {
            runEchoStress(rounds = FULL_ROUNDS)
        }
    }

    private suspend fun runEchoStress(rounds: Int) {
        val engine = NwEngine()
        try {
            val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
                channel.pipeline.addLast("echo", EchoHandler())
            }
            try {
                val port = (server.localAddress as InetSocketAddress).port

                coroutineScope {
                    (1..VU).map { vu ->
                        async(Dispatchers.Default) { runVu(engine, port, vu, rounds) }
                    }.awaitAll()
                }
            } finally {
                server.close()
            }
        } finally {
            engine.close()
        }
    }

    /**
     * One virtual user: connect once via [NwEngine.connect], perform [rounds]
     * sequential `Channel.write` + `Channel.read` round-trips, close. A VU-tagged
     * payload (`vu=<i> r=<j>`) makes a misrouted echo surface immediately as an
     * `assertEquals` mismatch.
     */
    private suspend fun runVu(engine: NwEngine, port: Int, vu: Int, rounds: Int) {
        val ch = engine.connect("127.0.0.1", port)
        try {
            repeat(rounds) { round ->
                val payload = "vu=$vu r=$round\n".encodeToByteArray()

                val writeBuf = ch.allocator.allocate(payload.size)
                for (b in payload) writeBuf.writeByte(b)
                ch.write(writeBuf)
                ch.flush()

                val readBuf = ch.allocator.allocate(payload.size)
                var totalRead = 0
                while (totalRead < payload.size) {
                    val n = ch.read(readBuf)
                    if (n < 0) error("VU $vu round $round EOF after $totalRead/${payload.size} bytes")
                    totalRead += n
                }
                val echoed = ByteArray(payload.size) { i -> readBuf.getByte(readBuf.readerIndex + i) }
                assertEquals(
                    payload.decodeToString(),
                    echoed.decodeToString(),
                    "VU $vu round $round echo mismatch (funnel routed wrong fd?)",
                )
                readBuf.release()
            }
        } finally {
            ch.close()
        }
    }

    private companion object {
        const val VU = 50
        const val QUICK_ROUNDS = 20
        const val FULL_ROUNDS = 100
        val QUICK_BUDGET: Duration = 30.seconds
        val FULL_BUDGET: Duration = 5.minutes

        fun stressMode(): String? = getenv("KEEL_STRESS")?.toKString()
    }
}
