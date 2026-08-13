package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.LoggerFactory
import io.github.fukusaka.keel.logging.NoopLoggerFactory
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Which tag this engine's own log statements carry.
 *
 * A `LoggerFactory` is free to route or filter by tag, and an application's
 * level configuration is keyed on it. When the two readiness engines were given
 * one shared base, the engine-level logger took the base class's name, so kqueue
 * and epoll became indistinguishable in the log and every rule keyed on
 * `KqueueEngine` stopped matching — silently, because a tag that matches nothing
 * reads the same as a component that has nothing to say.
 *
 * The tag reaches the base as a constructor argument rather than an abstract
 * member: the logger is built in the base's constructor, and a base constructor
 * must not read what the subclass has yet to assign.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueEngineLogTagTest {

    private class RecordingLoggerFactory : LoggerFactory {
        val tags = mutableListOf<String>()

        override fun logger(tag: String): Logger {
            tags += tag
            return NoopLoggerFactory.logger(tag)
        }
    }

    @Test
    fun `engine asks for its own name and not the base it shares`() = runBlocking {
        withTimeout(ENGINE_CLOSE_TIMEOUT) {
            val factory = RecordingLoggerFactory()
            val engine = KqueueEngine(IoEngineConfig(loggerFactory = factory))
            try {
                assertTrue(
                    "KqueueEngine" in factory.tags,
                    "expected the engine to log under its own name; asked for ${factory.tags}",
                )
                assertTrue(
                    factory.tags.none { it.startsWith("Abstract") },
                    "expected no base-class name as a tag; asked for ${factory.tags}",
                )
            } finally {
                engine.close()
            }
        }
    }

    private companion object {
        /** Construction and close are local; this only has to outlast a loaded CI runner. */
        val ENGINE_CLOSE_TIMEOUT = 5.seconds
    }
}
