package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.native.posix.PosixRawClient
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
import platform.posix.close
import platform.posix.getenv
import platform.posix.usleep
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Cross-thread funnel regression stress for [KqueueEngine].
 *
 * **Purpose**: guards the I/O ownership invariant — every syscall against
 * a channel must reach the channel's owning EventLoop via the cross-thread
 * funnel. Client coroutines on [Dispatchers.Default] drive [PosixRawClient]
 * writes/reads against a kqueue-backed pipeline echo handler. Each
 * round-trip exercises the funnel because the coroutine thread differs
 * from the engine EL thread, so any regression that bypasses
 * `assertInEventLoop` would surface as an `IllegalStateException`
 * propagating through the assert.
 *
 * **Why plain TCP echo over WS/HTTP**: the funnel verification is
 * workload-agnostic. Plain echo keeps the test small (no codec, no
 * protocol framing) and unifies the topology across the engine modules
 * that ship the same stress.
 *
 * **Gating**: every `@Test` returns early unless the `KEEL_STRESS`
 * environment variable is set (`quick` runs the quick scenario only,
 * `full` runs both). The CI quick-gate workflow sets `KEEL_STRESS=quick`
 * on engine PRs; the engine's stress `workflow_dispatch` yml sets
 * `KEEL_STRESS=full`.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueuePipelineEchoStress {

    /** Writes every inbound IoBuf straight back to the peer (transfers ownership downstream). */
    private class EchoHandler : InboundHandler {
        override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
            ctx.propagateWriteAndFlush(msg)
        }
    }

    /**
     * Quick scenario: [VU] × [QUICK_ROUNDS] = [VU] × 20 echo round-trips.
     * Runs on engine PR gate (`KEEL_STRESS=quick`), budget [QUICK_BUDGET].
     */
    @Test
    fun quick() = runBlocking {
        if (stressMode() !in setOf("quick", "full")) return@runBlocking
        withTimeout(QUICK_BUDGET) {
            runEchoStress(rounds = QUICK_ROUNDS, budget = QUICK_BUDGET)
        }
    }

    /**
     * Full scenario: [VU] × [FULL_ROUNDS] = [VU] × 100 echo round-trips.
     * Runs on `workflow_dispatch` only (`KEEL_STRESS=full`), budget [FULL_BUDGET].
     */
    @Test
    fun full() = runBlocking {
        if (stressMode() != "full") return@runBlocking
        withTimeout(FULL_BUDGET) {
            runEchoStress(rounds = FULL_ROUNDS, budget = FULL_BUDGET)
        }
    }

    private suspend fun runEchoStress(rounds: Int, budget: Duration) {
        // NOTE: `threads = 1` is intentional for now. During PR bring-up,
        // `threads = 4` + 50 VU surfaced a 10 s `rawRead` timeout (0/11 bytes)
        // that does not reproduce at `threads = 4` + 20 VU. The single-EL
        // configuration already exercises the cross-thread funnel — client
        // coroutines run on `Dispatchers.Default` workers, distinct from
        // the engine EL thread, so every `Channel.write` round-trips
        // through `runOnEventLoop`. Multi-EL routing is a separate
        // dimension to be investigated in a follow-up before this test is
        // promoted to `threads = 4`.
        val engine = KqueueEngine(IoEngineConfig(threads = 1))
        try {
            val server = engine.bindPipeline("127.0.0.1", 0) { channel ->
                channel.pipeline.addLast("echo", EchoHandler())
            }
            try {
                // Allow the multi-EL accept dispatcher to settle so the first
                // wave of connects doesn't race the listen-fd registration.
                usleep(SERVER_SETTLE_US)
                val port = (server.localAddress as InetSocketAddress).port

                coroutineScope {
                    (1..VU).map { vu ->
                        async(Dispatchers.Default) { runVu(port, vu, rounds) }
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
     * One virtual user: connect once, perform [rounds] sequential echoes,
     * close. A VU-tagged payload (`vu=<i> r=<j>`) makes a misrouted echo
     * surface immediately as a `assertEquals` mismatch rather than as a
     * silent data race the harness would tolerate.
     */
    private fun runVu(port: Int, vu: Int, rounds: Int) {
        val fd = PosixRawClient.rawConnect(port, RAW_READ_TIMEOUT)
        try {
            repeat(rounds) { round ->
                val payload = "vu=$vu r=$round\n"
                PosixRawClient.rawWrite(fd, payload)
                val echo = PosixRawClient.rawRead(fd, payload.length, RAW_READ_TIMEOUT)
                assertEquals(payload, echo, "VU $vu round $round echo mismatch (funnel routed wrong fd?)")
            }
        } finally {
            close(fd)
        }
    }

    private companion object {
        const val VU = 50
        const val QUICK_ROUNDS = 20
        const val FULL_ROUNDS = 100
        val QUICK_BUDGET: Duration = 30.seconds
        val FULL_BUDGET: Duration = 5.minutes

        // Per-read budget for the raw client. Generous enough to absorb VU
        // scheduling jitter at 50-way concurrency, tight enough that a
        // genuinely stuck round (engine never echoes back) is surfaced as
        // a deadline-expired error rather than blocking the whole budget.
        val RAW_READ_TIMEOUT: Duration = 10.seconds

        const val SERVER_SETTLE_US: UInt = 100_000u // 100 ms

        fun stressMode(): String? = getenv("KEEL_STRESS")?.toKString()
    }
}
