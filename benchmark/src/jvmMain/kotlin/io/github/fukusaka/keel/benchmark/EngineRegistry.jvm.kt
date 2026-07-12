package io.github.fukusaka.keel.benchmark

/** JVM default engine — keel-nio (pure Java NIO). */
actual fun defaultEngine(): String = "ktor-keel-nio"

/**
 * JVM engine registry: keel, pipeline, Ktor, Netty raw, Spring, Vert.x
 * variants. The `-io-uring` suffixed Netty entries pin
 * [io.github.fukusaka.keel.engine.netty.NettyTransport.IoUring] (Netty's
 * mainline `io.netty.channel.uring` transport, merged in Netty 4.2 — not
 * to be confused with `pipeline-http-io-uring` / `ktor-keel-io-uring`,
 * which are keel's own Kotlin/Native cinterop `keel-engine-io-uring`).
 */
actual fun engineRegistry(): Map<String, EngineBenchmark> = mapOf(
    "ktor-keel-nio" to KeelNioEngine,
    "ktor-cio-keel-nio" to KeelCioNioEngine,
    "pipeline-http-nio" to PipelineHttpNioBenchmark,
    "server-http-nio" to ServerHttpNioBenchmark,
    "ktor-keel-netty" to KeelNettyEngine,
    "ktor-keel-netty-io-uring" to KeelNettyIoUringEngine,
    "ktor-cio-keel-netty" to KeelCioNettyEngine,
    "pipeline-http-netty" to PipelineHttpNettyBenchmark,
    "pipeline-http-netty-io-uring" to PipelineHttpNettyIoUringBenchmark,
    "server-http-netty" to ServerHttpNettyBenchmark,
    "ktor-cio" to CioEngine,
    "ktor-netty" to KtorNettyEngine,
    "netty-raw" to NettyRawEngine,
    "spring" to SpringEngine,
    "vertx" to VertxEngine,
)
