package io.github.keel.benchmark

/** JVM default engine — keel-nio (pure Java NIO). */
actual fun defaultEngine(): String = "ktor-keel-nio"

/** JVM engine registry: keel, Ktor, Netty raw, Spring, Vert.x variants. */
actual fun engineRegistry(): Map<String, EngineBenchmark> = mapOf(
    "ktor-keel-nio" to KeelNioEngine,
    "ktor-keel-netty" to KeelNettyEngine,
    "ktor-cio" to CioEngine,
    "ktor-netty" to KtorNettyEngine,
    "netty-raw" to NettyRawEngine,
    "spring" to SpringEngine,
    "vertx" to VertxEngine,
)
