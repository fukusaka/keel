package io.github.keel.ktor

import io.github.keel.core.IoEngine
import io.github.keel.engine.kqueue.KqueueEngine

internal actual fun defaultEngine(): IoEngine = KqueueEngine()
