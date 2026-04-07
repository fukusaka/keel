package io.github.fukusaka.keel.benchmark

/** JVM default engine — keel-nio (pure Java NIO). */
actual fun defaultEngine(): String = "ktor-keel-nio"

/** JVM engine registry: keel, pipeline, Ktor, Netty raw, Spring, Vert.x variants. */
actual fun engineRegistry(): Map<String, EngineBenchmark> = mapOf(
    "ktor-keel-nio" to KeelNioEngine,
    "pipeline-http-nio" to PipelineHttpNioBenchmark,
    "ktor-keel-netty" to KeelNettyEngine,
    "ktor-cio" to CioEngine,
    "ktor-netty" to KtorNettyEngine,
    "netty-raw" to NettyRawEngine,
    "spring" to SpringEngine,
    "vertx" to VertxEngine,
)
