package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.epoll.EpollEngine
import io.github.fukusaka.keel.io.SlabAllocator

internal actual fun defaultEngine(): IoEngine = EpollEngine(IoEngineConfig(allocator = SlabAllocator()))
