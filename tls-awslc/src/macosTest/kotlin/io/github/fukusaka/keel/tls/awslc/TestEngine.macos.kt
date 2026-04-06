package io.github.fukusaka.keel.tls.awslc

import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.kqueue.KqueueEngine

actual fun createTestEngine(): IoEngine = KqueueEngine(IoEngineConfig(threads = 1))
