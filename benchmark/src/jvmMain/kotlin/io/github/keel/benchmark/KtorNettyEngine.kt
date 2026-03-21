package io.github.keel.benchmark

import io.netty.channel.ChannelOption
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty as KtorNetty

/** Ktor Netty engine settings. */
data class KtorNettyEngineConfig(
    val runningLimit: Int? = null,
    val shareWorkGroup: Boolean? = null,
) : EngineConfig {
    override fun displayTo(sb: StringBuilder, engine: String) {
        val nettyDefault = io.ktor.server.netty.NettyApplicationEngine.Configuration()
        sb.appendLine("--- Engine-Specific (ktor-netty) ---")
        sb.fmtLine("running-limit:", runningLimit?.toString() ?: "${nettyDefault.runningLimit} (default by Netty)")
        sb.fmtLine("share-work-group:", shareWorkGroup?.toString() ?: "${nettyDefault.shareWorkGroup} (default by Netty)")
    }

    override fun toString(): String = buildString {
        runningLimit?.let { append("runningLimit=$it") }
        shareWorkGroup?.let { if (isNotEmpty()) append(", "); append("shareWorkGroup=$it") }
    }
}

/** Ktor Netty engine. */
object KtorNettyEngine : EngineBenchmark {
    override fun start(config: BenchmarkConfig) {
        val netty = config.engineConfig as? KtorNettyEngineConfig ?: KtorNettyEngineConfig()
        val s = config.socket
        val rootConfig = serverConfig {
            module { benchmarkModule(config.connectionClose) }
        }
        embeddedServer(KtorNetty, rootConfig) {
            connector { this.port = config.port }
            s.threads?.let {
                workerGroupSize = it
                callGroupSize = it
            }
            netty.runningLimit?.let { runningLimit = it }
            netty.shareWorkGroup?.let { shareWorkGroup = it }
            configureBootstrap = {
                s.tcpNoDelay?.let { childOption(ChannelOption.TCP_NODELAY, it) }
                s.backlog?.let { option(ChannelOption.SO_BACKLOG, it) }
                s.sendBuffer?.let { childOption(ChannelOption.SO_SNDBUF, it) }
                s.receiveBuffer?.let { childOption(ChannelOption.SO_RCVBUF, it) }
                s.reuseAddress?.let { option(ChannelOption.SO_REUSEADDR, it) }
            }
        }.start(wait = true)
    }

    override fun tunedSocket(s: SocketConfig, cpuCores: Int): SocketConfig = s.copy(
        tcpNoDelay = s.tcpNoDelay ?: true,
        backlog = s.backlog ?: TUNED_BACKLOG,
        reuseAddress = s.reuseAddress ?: true,
        threads = s.threads ?: cpuCores,
    )

    override fun tunedConfig(config: BenchmarkConfig, cpuCores: Int): BenchmarkConfig = config.copy(
        engineConfig = KtorNettyEngineConfig(
            runningLimit = cpuCores * 16,
            shareWorkGroup = false,
        )
    )

    override fun mergeConfig(base: EngineConfig, args: Map<String, String>): EngineConfig {
        val b = base as? KtorNettyEngineConfig ?: KtorNettyEngineConfig()
        return KtorNettyEngineConfig(
            runningLimit = args["running-limit"]?.toInt() ?: b.runningLimit,
            shareWorkGroup = args["share-work-group"]?.toBooleanStrict() ?: b.shareWorkGroup,
        )
    }

    override fun socketDefaults(os: OsSocketDefaults): SocketConfig.SocketDefaults {
        val workerThreads = io.ktor.server.netty.NettyApplicationEngine.Configuration().workerGroupSize
        return SocketConfig.SocketDefaults(
            tcpNoDelay = "${os.tcpNoDelay} (default by OS, via Netty)",
            reuseAddress = "${os.reuseAddress} (default by OS, via Netty)",
            backlog = "${os.backlog} (default by OS, estimated)",
            sendBuffer = "${os.sendBuffer} bytes (default by OS)",
            receiveBuffer = "${os.receiveBuffer} bytes (default by OS)",
            threads = if (workerThreads > 0) "$workerThreads (default by Netty, workerGroupSize)" else "${availableProcessors() * 2} (default by Netty, cpu*2)",
        )
    }
}
