package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.core.IoEngine
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.io.PooledDirectAllocator

internal actual fun defaultEngine(): IoEngine = NioEngine(IoEngineConfig(allocator = PooledDirectAllocator()))
