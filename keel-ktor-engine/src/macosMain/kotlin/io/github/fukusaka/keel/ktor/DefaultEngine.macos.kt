package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.engine.kqueue.KqueueEngine

internal actual fun defaultEngine(): StreamEngine = KqueueEngine(IoEngineConfig())
