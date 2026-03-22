package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.engine.epoll.EpollEngine

internal actual fun defaultEngine(): IoEngine = EpollEngine()
