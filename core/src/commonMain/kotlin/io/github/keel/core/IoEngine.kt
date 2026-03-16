package io.github.keel.core

/**
 * Platform-specific I/O event loop engine.
 *
 * Implementations: epoll (Linux), kqueue (macOS), NIO (JVM), Netty (JVM),
 * Node.js net (JS), NWConnection (Apple).
 */
expect class IoEngine() {
    /**
     * Polls for I/O events and processes them.
     *
     * @param timeoutMs Maximum wait time in milliseconds; 0 returns immediately.
     * @return Number of events processed.
     */
    fun poll(timeoutMs: Long): Int

    /** Closes the engine and releases all resources. */
    fun close()
}
