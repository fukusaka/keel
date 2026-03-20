package io.github.keel.ktor

import io.github.keel.core.IoEngine
import io.github.keel.engine.nio.NioEngine

internal actual fun defaultEngine(): IoEngine = NioEngine()
