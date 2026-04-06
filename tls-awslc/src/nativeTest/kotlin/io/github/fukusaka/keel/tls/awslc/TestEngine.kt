package io.github.fukusaka.keel.tls.awslc

import io.github.fukusaka.keel.core.IoEngine

/**
 * Creates a platform-specific [IoEngine] for integration tests.
 *
 * macOS: KqueueEngine, Linux: EpollEngine.
 */
expect fun createTestEngine(): IoEngine
