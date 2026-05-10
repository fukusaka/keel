package io.github.fukusaka.keel.server.ktor.cio

/**
 * JVM no-op pass-through.  `synchronized` inside ktor's `HeadersDataPool`
 * is reentrant and JIT-optimised on the JVM, so concurrent header-parse
 * calls do not exhibit the contention storm seen on Kotlin/Native.
 */
internal actual open class HeaderParseMutex actual constructor() {
    actual open suspend fun <T> withLock(block: suspend () -> T): T = block()
}
