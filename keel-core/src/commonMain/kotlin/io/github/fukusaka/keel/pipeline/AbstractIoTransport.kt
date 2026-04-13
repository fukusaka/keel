package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.BufferAllocator
import kotlinx.coroutines.CoroutineDispatcher

/**
 * Base class for [IoTransport] implementations with shared defaults.
 *
 * Provides:
 * - [appDispatcher] defaults to [ioDispatcher] (NIO overrides to Dispatchers.Default)
 * - [supportsDeferredFlush] defaults to `true` (Node.js overrides to `false`)
 * - [awaitPendingFlush] defaults to no-op (sync transports like NWConnection, Node.js)
 * - [awaitClosed] defaults to no-op (sync close transports)
 * - [isOpen] backed by [opened] flag with idempotent close guard
 * - Callback properties initialized to `null`
 *
 * Engine implementations extend this class and override platform-specific
 * members: [readEnabled] setter, [write], [flush], [shutdownOutput], [close].
 *
 * @param allocator Buffer allocator for read operations.
 */
abstract class AbstractIoTransport(
    override val allocator: BufferAllocator,
) : IoTransport {

    // --- Open state ---

    protected var opened = true
    override val isOpen: Boolean get() = opened

    // --- Read path callbacks ---

    override var onRead: ((io.github.fukusaka.keel.buf.IoBuf) -> Unit)? = null
    override var onReadClosed: (() -> Unit)? = null

    // --- Write path callbacks ---

    override var onFlushComplete: (() -> Unit)? = null
    override var onWritabilityChanged: ((Boolean) -> Unit)? = null

    // --- Defaults ---

    override val appDispatcher: CoroutineDispatcher get() = ioDispatcher
    override val supportsDeferredFlush: Boolean get() = true
    override suspend fun awaitPendingFlush() {}
    override suspend fun awaitClosed() {}
}
