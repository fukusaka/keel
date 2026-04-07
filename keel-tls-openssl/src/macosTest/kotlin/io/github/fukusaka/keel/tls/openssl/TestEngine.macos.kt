package io.github.fukusaka.keel.tls.openssl

import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.kqueue.KqueueEngine

actual fun createTestEngine(): StreamEngine = KqueueEngine(IoEngineConfig(threads = 1))
