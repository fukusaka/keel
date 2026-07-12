package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.core.BindConfig
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.netty.NettyEngine
import io.github.fukusaka.keel.engine.netty.NettyTransport
import kotlinx.coroutines.runBlocking

/**
 * Same pipeline as [PipelineHttpNettyBenchmark], but pinned to
 * [NettyTransport.IoUring] instead of the default [NettyTransport.Auto]
 * (epoll on Linux). Requires Linux 5.1+; fails fast at [NettyEngine]
 * construction if `io.netty.channel.uring.IoUring.isAvailable()` is false.
 *
 * Not to be confused with `pipeline-http-io-uring`
 * ([PipelineHttpIoUringBenchmark]), which is keel's own Kotlin/Native
 * cinterop io_uring engine (`keel-engine-io-uring`) — this benchmark
 * instead exercises Netty's mainline `io.netty.channel.uring` transport
 * on the JVM.
 */
object PipelineHttpNettyIoUringBenchmark : EngineBenchmark {

    override fun start(config: BenchmarkConfig): () -> Unit {
        val threads = config.socket.threads ?: 0
        val engine = NettyEngine(
            config = IoEngineConfig(
                threads = threads,
                loggerFactory = benchmarkLoggerFactory(),
            ),
            nettyTransport = NettyTransport.IoUring,
        )

        val (tlsBindConfig, tlsCloseable) = if (config.tls != null) createTlsBindConfig(config) else (BindConfig() to null)

        val server = engine.bindPipeline("0.0.0.0", config.port, config = tlsBindConfig) { channel ->
            installPipelineHttpHandlers(channel.pipeline, compression = config.compression)
        }

        return {
            server.close()
            tlsCloseable?.close()
            runBlocking { engine.close() }
        }
    }

    override fun socketDefaults(os: OsSocketDefaults): SocketConfig.SocketDefaults {
        return SocketConfig.SocketDefaults(
            tcpNoDelay = "(not configurable, Netty default)",
            reuseAddress = "(not configurable, Netty default)",
            backlog = "(not configurable, Netty default: 128)",
            sendBuffer = "(not configurable, OS: ${os.sendBuffer} bytes)",
            receiveBuffer = "(not configurable, OS: ${os.receiveBuffer} bytes)",
            threads = "${Runtime.getRuntime().availableProcessors() * 2} (Netty default: cpu * 2)",
        )
    }
}
