package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.engine.epoll.EpollEngine

internal actual fun defaultEngine(): StreamEngine = EpollEngine(IoEngineConfig())
