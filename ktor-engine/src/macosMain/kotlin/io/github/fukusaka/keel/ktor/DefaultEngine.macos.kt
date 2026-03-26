package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.kqueue.KqueueEngine
import io.github.fukusaka.keel.io.SlabAllocator

internal actual fun defaultEngine(): IoEngine = KqueueEngine(IoEngineConfig(allocator = SlabAllocator()))
