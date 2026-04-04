package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.epoll.EpollEngine
import io.github.fukusaka.keel.pipeline.ChannelPipeline

actual fun createTestEngine(): TestEngine = EpollTestEngine()

private class EpollTestEngine : TestEngine {
    private val engine = EpollEngine(IoEngineConfig(threads = 1))

    override fun bindPipeline(
        host: String,
        port: Int,
        pipelineInitializer: (ChannelPipeline) -> Unit,
    ): AutoCloseable = engine.bindPipeline(host, port, pipelineInitializer)

    override fun close() = engine.close()
}
