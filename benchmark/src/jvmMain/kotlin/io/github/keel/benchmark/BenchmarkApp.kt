package io.github.keel.benchmark

import io.github.keel.engine.netty.NettyEngine
import io.github.keel.ktor.Keel
import io.netty.channel.ChannelOption
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.netty.Netty as KtorNetty

/**
 * CLI entry point for benchmark servers.
 *
 * Usage:
 *   --engine=keel|keel-netty|cio|ktor-netty|spring|vertx
 *   --port=8080
 *   --profile=default|tuned|keel-equiv-0.1
 *   --connection-close=true       Force Connection: close
 *   --tcp-nodelay=true            TCP_NODELAY
 *   --reuse-address=true          SO_REUSEADDR
 *   --backlog=1024                SO_BACKLOG
 *   --send-buffer=65536           SO_SNDBUF
 *   --receive-buffer=65536        SO_RCVBUF
 *   --threads=N                   Worker thread count
 *   --running-limit=32            Ktor Netty concurrent request limit
 *   --share-work-group=true       Ktor Netty share connection/worker groups
 *   --idle-timeout=45             CIO idle connection timeout (seconds)
 *   --show-config                 Display resolved config and exit
 *
 * Profiles:
 *   default          — each engine's out-of-box settings
 *   tuned            — maximum performance (TCP_NODELAY, higher backlog)
 *   keel-equiv-0.1   — match keel 0.1.x Phase (a): Connection: close, sync I/O
 *   keel-equiv-0.2   — match keel 0.2.x Phase (b): keep-alive, async I/O (future)
 */
fun main(args: Array<String>) {
    val config = BenchmarkConfig.parse(args)

    if (config.showConfig) {
        print(config.display())
        return
    }

    println("Starting benchmark server: ${config.summary()}")

    when (config.engine) {
        "keel" -> startKeel(config)
        "keel-netty" -> startKeelNetty(config)
        "cio" -> startCio(config)
        "ktor-netty" -> startKtorNetty(config)
        "spring" -> startSpring(config)
        "vertx" -> startVertx(config)
        else -> {
            System.err.println("Unknown engine: ${config.engine}")
            System.err.println("Available: keel, keel-netty, cio, ktor-netty, spring, vertx")
            kotlin.system.exitProcess(1)
        }
    }
}

private fun startKeel(config: BenchmarkConfig) {
    embeddedServer(Keel, port = config.port) {
        benchmarkModule(config.connectionClose)
    }.start(wait = true)
}

private fun startKeelNetty(config: BenchmarkConfig) {
    val rootConfig = serverConfig {
        module { benchmarkModule(config.connectionClose) }
    }
    embeddedServer(Keel, rootConfig) {
        connector { this.port = config.port }
        engine = NettyEngine()
    }.start(wait = true)
}

private fun startCio(config: BenchmarkConfig) {
    val rootConfig = serverConfig {
        module { benchmarkModule(config.connectionClose) }
    }
    embeddedServer(CIO, rootConfig) {
        connector { this.port = config.port }
        config.reuseAddress?.let { reuseAddress = it }
        config.idleTimeout?.let { connectionIdleTimeoutSeconds = it }
    }.start(wait = true)
}

private fun startKtorNetty(config: BenchmarkConfig) {
    val rootConfig = serverConfig {
        module { benchmarkModule(config.connectionClose) }
    }
    embeddedServer(KtorNetty, rootConfig) {
        connector { this.port = config.port }
        config.threads?.let {
            workerGroupSize = it
            callGroupSize = it
        }
        config.runningLimit?.let { runningLimit = it }
        config.shareWorkGroup?.let { shareWorkGroup = it }
        configureBootstrap = {
            config.tcpNoDelay?.let { childOption(ChannelOption.TCP_NODELAY, it) }
            config.backlog?.let { option(ChannelOption.SO_BACKLOG, it) }
            config.sendBuffer?.let { childOption(ChannelOption.SO_SNDBUF, it) }
            config.receiveBuffer?.let { childOption(ChannelOption.SO_RCVBUF, it) }
            config.reuseAddress?.let { option(ChannelOption.SO_REUSEADDR, it) }
        }
    }.start(wait = true)
}
