package io.github.fukusaka.keel.tls.openssl

import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.epoll.EpollEngine

actual fun createTestEngine(): StreamEngine = EpollEngine(IoEngineConfig(threads = 1))
