package io.github.fukusaka.keel.benchmark

/**
 * CLI entry point for benchmark servers.
 *
 * Usage:
 *   --engine=keel-nio|keel-netty|ktor-cio|ktor-netty|netty-raw|spring|vertx
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
 *   --connection-idle-timeout=45   CIO idle connection timeout (seconds)
 *   --max-content-length=65536    Netty raw max HTTP content length
 *   --show-config                 Display resolved config and exit
 *
 * Profiles:
 *   default          — each engine's out-of-box settings
 *   tuned            — maximum performance (TCP_NODELAY, higher backlog)
 *   keel-equiv-0.1   — match keel 0.1.x Phase (a): Connection: close, sync I/O
 *   keel-equiv-0.2   — match keel 0.2.x Phase (b): keep-alive, async I/O (future)
 */
fun main(args: Array<String>) {
    val engines = engineRegistry()
    val config = BenchmarkConfig.parse(args)

    if (config.engine !in engines) {
        System.err.println("Unknown engine: ${config.engine}")
        System.err.println("Available: ${engines.keys.joinToString(", ")}")
        kotlin.system.exitProcess(1)
    }

    if (config.showConfig) {
        print(config.display())
        return
    }

    println("Starting benchmark server: ${config.summary()}")
    engines[config.engine]!!.start(config)
}
