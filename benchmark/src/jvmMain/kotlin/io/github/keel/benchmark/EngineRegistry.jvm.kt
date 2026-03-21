package io.github.keel.benchmark

/** JVM default engine — keel-nio (pure Java NIO). */
actual fun defaultEngine(): String = "keel-nio"

/** JVM engine registry: keel, Ktor, Netty raw, Spring, Vert.x variants. */
actual fun engineRegistry(): Map<String, EngineBenchmark> = mapOf(
    "keel-nio" to KeelNioEngine,
    "keel-netty" to KeelNettyEngine,
    "ktor-cio" to CioEngine,
    "ktor-netty" to KtorNettyEngine,
    "netty-raw" to NettyRawEngine,
    "spring" to SpringEngine,
    "vertx" to VertxEngine,
)
