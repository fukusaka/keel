package io.github.fukusaka.keel.tls.mbedtls

import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.epoll.EpollEngine

actual fun createTestEngine(): IoEngine = EpollEngine(IoEngineConfig(threads = 1))
