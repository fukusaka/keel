package io.github.fukusaka.keel.ktor

import io.github.fukusaka.keel.core.StreamEngine

/**
 * Creates the platform-default [StreamEngine] instance.
 *
 * - JVM: [io.github.fukusaka.keel.engine.nio.NioEngine]
 * - macOS: [io.github.fukusaka.keel.engine.kqueue.KqueueEngine]
 */
internal expect fun defaultEngine(): StreamEngine
