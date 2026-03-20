package io.github.keel.ktor

import io.github.keel.core.IoEngine

/**
 * Creates the platform-default [IoEngine] instance.
 *
 * - JVM: [io.github.keel.engine.nio.NioEngine]
 * - macOS: [io.github.keel.engine.kqueue.KqueueEngine]
 */
internal expect fun defaultEngine(): IoEngine
