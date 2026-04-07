package io.github.fukusaka.keel.tls.awslc

import io.github.fukusaka.keel.core.StreamEngine

/**
 * Creates a platform-specific [StreamEngine] for integration tests.
 *
 * macOS: KqueueEngine, Linux: EpollEngine.
 */
expect fun createTestEngine(): StreamEngine
