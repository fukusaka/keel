package io.github.keel.ktor

import io.github.keel.core.IoEngine
import io.github.keel.engine.epoll.EpollEngine

internal actual fun defaultEngine(): IoEngine = EpollEngine()
