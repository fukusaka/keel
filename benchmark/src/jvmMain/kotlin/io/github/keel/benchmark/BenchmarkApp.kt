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
 *   --engine=keel          keel Ktor adapter with NioEngine (default)
 *   --engine=keel-netty    keel Ktor adapter with NettyEngine
 *   --engine=cio           Ktor CIO engine
 *   --engine=ktor-netty    Ktor Netty engine
 *   --engine=spring        Spring Boot WebFlux (Netty)
 *   --engine=vertx         Vert.x HTTP server
 *   --port=8080            Server port (default: 8080)
 */
fun main(args: Array<String>) {
    val engineName = args.firstOrNull { it.startsWith("--engine=") }
        ?.substringAfter("=") ?: "keel"
    val port = args.firstOrNull { it.startsWith("--port=") }
        ?.substringAfter("=")?.toInt() ?: 8080

    println("Starting benchmark server: engine=$engineName, port=$port")

    when (engineName) {
        "keel" -> startKeel(port)
        "keel-netty" -> startKeelNetty(port)
        "cio" -> startCio(port)
        "ktor-netty" -> startKtorNetty(port)
        "spring" -> startSpring(port)
        "vertx" -> startVertx(port)
        else -> {
            System.err.println("Unknown engine: $engineName")
            System.err.println("Available: keel, keel-netty, cio, ktor-netty, spring, vertx")
            kotlin.system.exitProcess(1)
        }
    }
}

private fun startKeel(port: Int) {
    embeddedServer(Keel, port = port) { benchmarkModule() }.start(wait = true)
}

private fun startKeelNetty(port: Int) {
    val rootConfig = serverConfig {
        module { benchmarkModule() }
    }
    embeddedServer(Keel, rootConfig) {
        connector { this.port = port }
        engine = NettyEngine()
    }.start(wait = true)
}

private fun startCio(port: Int) {
    embeddedServer(CIO, port = port) { benchmarkModule() }.start(wait = true)
}

private fun startKtorNetty(port: Int) {
    embeddedServer(KtorNetty, port = port) { benchmarkModule() }.start(wait = true)
}
