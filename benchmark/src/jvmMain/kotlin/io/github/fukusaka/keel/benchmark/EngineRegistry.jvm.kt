package io.github.fukusaka.keel.benchmark

/** JVM default engine — keel-nio (pure Java NIO). */
actual fun defaultEngine(): String = "ktor-keel-nio"

/** JVM engine registry: keel, pipeline, Ktor, Netty raw, Spring, Vert.x variants. */
actual fun engineRegistry(): Map<String, EngineBenchmark> = mapOf(
    "ktor-keel-nio" to KeelNioEngine,
    "ktor-cio-keel-nio" to KeelCioNioEngine,
    "pipeline-http-nio" to PipelineHttpNioBenchmark,
    "server-http-nio" to ServerHttpNioBenchmark,
    "ktor-keel-netty" to KeelNettyEngine,
    "ktor-cio-keel-netty" to KeelCioNettyEngine,
    "pipeline-http-netty" to PipelineHttpNettyBenchmark,
    "server-http-netty" to ServerHttpNettyBenchmark,
    "ktor-cio" to CioEngine,
    "ktor-netty" to KtorNettyEngine,
    "netty-raw" to NettyRawEngine,
    "spring" to SpringEngine,
    "vertx" to VertxEngine,
)
