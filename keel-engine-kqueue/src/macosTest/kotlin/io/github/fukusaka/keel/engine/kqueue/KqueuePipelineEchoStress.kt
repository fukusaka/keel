@file:OptIn(InternalReadinessEngineApi::class)

package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.native.readiness.InternalReadinessEngineApi
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
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
 * Cross-thread funnel regression stress for [KqueueEngine].
 *
 * **Purpose**: guards the I/O ownership invariant — every channel
 * operation must reach the channel's owning EventLoop via the
 * cross-thread funnel. Each virtual user holds a keel [Channel] obtained
 * from [KqueueEngine.connect] and drives `write` / `read` from a
 * [Dispatchers.Default] coroutine that is not the channel's `ioDispatcher`
 * thread; every call therefore round-trips through `runOnEventLoop`. A
 * regression that bypasses `assertInEventLoop` would surface as an
 * `IllegalStateException` propagating through the assert, and a
 * fd-routing regression would surface as an echo mismatch
 * (`vu=<i>` payload tagged so any misrouting fails `assertEquals`
 * immediately).
 *
 * **Multi-EL coverage (`threads = 4`)**: connections are distributed
 * round-robin across four worker EventLoops, so this also exercises
 * multi-EL accept dispatch and per-worker fd routing under concurrency —
 * the dimension a single-EventLoop configuration cannot reach. This
 * configuration previously stalled on a shared-collection defect (a
 * `LongObjectMap` backward-shift bug that lost a connection's fd-readiness
 * registration so its `connect` never resumed, fixed in #846); running at
 * `threads = 4` keeps that fix guarded for kqueue specifically.
 *
 * **Concurrent-allocate coverage**: each VU also allocates its `write` /
 * `read` buffers from `ch.allocator` on its [Dispatchers.Default]
 * coroutine while the engine allocates read buffers for the same channel
 * on the EventLoop thread — two threads allocating on one per-connection
 * allocator. During bring-up this stress hit a `VU >= 25` crash (a ~450×
 * cliff: `VU=10`×`R=100` passed in ~60 ms, `VU=25`×`R=20` timed out at the
 * 30 s budget). That was the cross-thread allocate race — the allocator's
 * shared cross-thread-return drain scratch corrupting into a double-free —
 * fixed by the per-call drain scratch in #836. The stress now passes
 * (`VU=50`×`R=20` in ~60 ms), so it doubles as an end-to-end regression
 * guard for that fix, complementing the allocator-level churn unit test.
 *
 * **Why a keel-self client (not a raw POSIX socket)**: the funnel is a
 * property of keel's Channel API, so the client must call into the API
 * to exercise it. A raw POSIX client bypasses the API entirely and
 * verifies only kernel-level echo, not the invariant. Using
 * `engine.connect` also keeps the client suspend-based, so 50 concurrent
 * VUs do not starve the underlying thread pool (a blocking POSIX client
 * exhausts the fixed-size Native `Dispatchers.Default` pool on Linux
 * once VU exceeds the worker count).
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
        // threads = 4: four worker EventLoops so the stress covers multi-EL
        // accept dispatch + per-worker fd routing on top of the cross-thread
        // funnel. The multi-EL hang seen during bring-up was the shared
        // LongObjectMap backward-shift bug (#846), not a kqueue limitation;
        // with that fixed this runs reliably at threads = 4.
        val engine = KqueueEngine(IoEngineConfig(threads = 4))
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
     * One virtual user: connect once via [KqueueEngine.connect], perform
     * [rounds] sequential `Channel.write` + `Channel.read` round-trips,
     * close. A VU-tagged payload (`vu=<i> r=<j>`) makes a misrouted echo
     * surface immediately as an `assertEquals` mismatch.
     */
    private suspend fun runVu(engine: KqueueEngine, port: Int, vu: Int, rounds: Int) {
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
