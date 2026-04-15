package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.iouring.IoModeSelectors
import io.github.fukusaka.keel.engine.iouring.IoUringEngine
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

/**
 * Pipeline HTTP benchmark using [IoUringEngine] with [HttpRequestDecoder],
 * [RoutingHandler], and [HttpResponseEncoder].
 *
 * The full pipeline path (decode → route → encode) is exercised on every request,
 * allowing measurement of the complete Pipeline HTTP overhead.
 *
 * Pipeline structure:
 * ```
 * HEAD ↔ [tls] ↔ encoder ↔ decoder ↔ routing ↔ TAIL
 * ```
 * - Inbound (HEAD→TAIL): decoder converts [IoBuf] → [HttpRequestHead] → routing handles it
 * - Outbound (routing→HEAD): encoder converts [HttpResponse] → [IoBuf] → IoTransport
 */
object PipelineHttpIoUringBenchmark : EngineBenchmark {

    @OptIn(ExperimentalForeignApi::class)
    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0 // 0 = auto (availableProcessors)
        val modeSelector = when (getenv("BENCH_IO_MODE")?.toKString()) {
            "cqe" -> IoModeSelectors.CQE
            "fallback" -> IoModeSelectors.FALLBACK_CQE
            "sendzc" -> IoModeSelectors.SEND_ZC
            "sendmsg-zc" -> IoModeSelectors.SENDMSG_ZC
            else -> IoModeSelectors.eagainThreshold() // default: adaptive
        }
        // Default-false capabilities: env var "true" enables, anything else leaves off.
        val registeredBuffers = getenv("BENCH_REGISTERED_BUFFERS")?.toKString() == "true"
        val deferTaskrun = getenv("BENCH_DEFER_TASKRUN")?.toKString() == "true"
        val msgRingWakeup = getenv("BENCH_MSG_RING_WAKEUP")?.toKString() == "true"
        // Default-true capabilities: env var "false" disables, anything else keeps on.
        val forceSingleIssuerOff = getenv("BENCH_SINGLE_ISSUER")?.toKString() == "false"
        val forceRegisterRingFdOff = getenv("BENCH_REGISTER_RING_FD")?.toKString() == "false"
        // Build an explicit capability set only when at least one env var
        // diverges from the default; otherwise leave caps null so the engine
        // picks up `IoUringCapabilities.detect(ring)` (auto-enabled
        // registerRingFd on kernel 5.18+, etc).
        val anyOverride = registeredBuffers || deferTaskrun || msgRingWakeup ||
            forceSingleIssuerOff || forceRegisterRingFdOff
        val caps = if (anyOverride) {
            io.github.fukusaka.keel.engine.iouring.IoUringCapabilities(
                registeredBuffers = registeredBuffers,
                deferTaskrun = deferTaskrun,
                msgRingWakeup = msgRingWakeup,
                registerRingFd = !forceRegisterRingFdOff,
                singleIssuer = !forceSingleIssuerOff,
            )
        } else {
            null
        }
        val engine = IoUringEngine(
            config = IoEngineConfig(
                threads = threads,
                loggerFactory = NoopLoggerFactory,
            ),
            writeModeSelector = modeSelector,
            capabilities = caps,
        )

        // Pre-built responses: headers and body are computed once at startup.
        // flatEntries cache is warmed here (before bindPipeline spawns EventLoop threads)
        // to avoid benign but unnecessary first-request computation on each thread.
        val (tlsBindConfig, tlsCloseable) = if (config.tls != null) createTlsBindConfig(config) else (BindConfig() to null)

        val server = engine.bindPipeline("0.0.0.0", config.port, config = tlsBindConfig) { channel ->
            installPipelineHttpHandlers(channel.pipeline)
        }

        return {
            server.close()
            tlsCloseable?.close()
            engine.close()
        }
    }

    override fun socketDefaults(os: OsSocketDefaults) = keelSocketDefaults(os)
}
