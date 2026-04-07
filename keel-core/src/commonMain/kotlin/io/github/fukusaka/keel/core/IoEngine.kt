package io.github.fukusaka.keel.core

/**
 * Root interface for all keel I/O engines.
 *
 * Provides access to the engine's [config] and lifecycle management
 * ([close]). Concrete transport models are defined by sub-interfaces:
 *
 * - [StreamEngine]: byte-stream transport (TCP, Unix SOCK_STREAM)
 * - `DatagramEngine` (future): message-oriented transport (UDP, Unix SOCK_DGRAM)
 *
 * ```
 * IoEngine (config + close)
 * ├── StreamEngine    ← TCP, Unix SOCK_STREAM, QUIC
 * └── DatagramEngine  ← UDP, Unix SOCK_DGRAM (future)
 * ```
 *
 * Engine implementations typically implement [StreamEngine] (and
 * optionally `DatagramEngine` when UDP support is added):
 *
 * ```
 * class EpollEngine : StreamEngine { ... }
 * ```
 *
 * @see StreamEngine
 * @see IoEngineConfig
 */
interface IoEngine : AutoCloseable {

    /** Engine-wide configuration (allocator, threads, logging). */
    val config: IoEngineConfig

    /** Closes the engine and releases all resources (kqueue fd, selector, etc.). */
    override fun close()
}
