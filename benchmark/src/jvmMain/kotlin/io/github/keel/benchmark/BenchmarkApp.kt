package io.github.keel.benchmark

import io.github.keel.engine.netty.NettyEngine
import io.github.keel.ktor.Keel
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
 *   --profile=default|tuned|keel-equiv
 *   --connection-close=true|false
 *   --tcp-nodelay=true|false
 *   --backlog=1024
 *   --threads=N
 *
 * Profiles:
 *   default    — each engine's out-of-box settings
 *   tuned      — maximum performance (TCP_NODELAY, higher backlog)
 *   keel-equiv — all engines constrained to match keel Phase (a) (Connection: close)
 */
fun main(args: Array<String>) {
    val config = BenchmarkConfig.parse(args)
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
    embeddedServer(CIO, port = config.port) {
        benchmarkModule(config.connectionClose)
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
    }.start(wait = true)
}
