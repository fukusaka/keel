package io.github.fukusaka.keel.engine.nio

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.pipeline.InboundHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-thread funnel regression stress for [NioEngine] at `threads = 4`.
 *
 * **Purpose**: guards the I/O ownership invariant — every channel operation
 * must reach the channel's owning EventLoop via the cross-thread funnel. Each
 * virtual user holds a keel [Channel] obtained from [NioEngine.connect] and
 * drives `write` / `read` from a [Dispatchers.Default] coroutine that is not
 * the channel's `ioDispatcher` thread; every call therefore round-trips through
 * `runOnEventLoop`. A regression that bypasses `assertInEventLoop` surfaces as
 * an `IllegalStateException` through the assert, and an fd-routing regression
 * surfaces as an echo mismatch (`vu=<i>` payload tagged so any misrouting fails
 * `assertEquals` immediately).
 *
 * **Multi-EL coverage (`threads = 4`)**: connections are distributed across
 * four selector EventLoops, so this also exercises multi-EL accept dispatch and
 * per-selector channel routing under concurrency — the dimension a single-EL
 * configuration cannot reach.
 *
 * **Concurrent-allocate coverage**: each VU allocates its `write` / `read`
 * buffers from `ch.allocator` on its [Dispatchers.Default] coroutine while the
 * engine allocates read buffers for the same channel on the EventLoop thread —
 * two threads allocating on one per-connection allocator. This end-to-end
 * exercises the JVM pooled-allocator concurrent-allocate path (the lock-free
 * freelist swap to a mutex freelist, #837), complementing the allocator-level
 * churn unit test.
 *
 * **Why a keel-self client (not a raw socket)**: the funnel is a property of
 * keel's Channel API, so the client must call into the API to exercise it. A
 * raw socket bypasses the API entirely and verifies only kernel-level echo, not
 * the invariant. `engine.connect` also keeps the client suspend-based, so 50
 * concurrent VUs do not starve the fixed-size [Dispatchers.Default] pool once VU
 * exceeds the worker count.
 *
 * **Gating**: every `@Test` returns early unless the `KEEL_STRESS` environment
 * variable is set (`quick` runs the quick scenario only, `full` runs both). The
 * CI quick-gate workflow sets `KEEL_STRESS=quick` on engine PRs; the engine's
 * stress `workflow_dispatch` yml sets `KEEL_STRESS=full`.
 */
class NioPipelineEchoStress {

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
    fun quick(): Unit = runBlocking {
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
    fun full(): Unit = runBlocking {
        if (stressMode() != "full") return@runBlocking
        withTimeout(FULL_BUDGET) {
            runEchoStress(rounds = FULL_ROUNDS)
        }
    }

    private suspend fun runEchoStress(rounds: Int) {
        val engine = NioEngine(IoEngineConfig(threads = 4))
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
     * One virtual user: connect once via [NioEngine.connect], perform [rounds]
     * sequential `Channel.write` + `Channel.read` round-trips, close. A VU-tagged
     * payload (`vu=<i> r=<j>`) makes a misrouted echo surface immediately as an
     * `assertEquals` mismatch.
     */
    private suspend fun runVu(engine: NioEngine, port: Int, vu: Int, rounds: Int) {
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

        fun stressMode(): String? = System.getenv("KEEL_STRESS")
    }
}
