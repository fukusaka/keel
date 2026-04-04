package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.pipeline.ChannelPipeline

/**
 * Platform-specific engine for HTTPS echo integration tests.
 *
 * macOS: KqueueEngine, Linux: EpollEngine.
 */
expect fun createTestEngine(): TestEngine

interface TestEngine : AutoCloseable {
    fun bindPipeline(
        host: String,
        port: Int,
        pipelineInitializer: (ChannelPipeline) -> Unit,
    ): AutoCloseable
}
