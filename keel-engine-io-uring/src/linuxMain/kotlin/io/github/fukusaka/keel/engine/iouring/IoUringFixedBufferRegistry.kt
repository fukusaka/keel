package io.github.fukusaka.keel.engine.iouring

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Per-EventLoop registry of buffers pre-registered with the io_uring kernel
 * ring for `IORING_OP_SEND_ZC_FIXED` use. The runtime [RegisteredBufferStrategy]
 * picks the implementation; the engine never sees the strategy directly.
 *
 * The interface is intentionally minimal — the engine's write path needs
 * exactly one question answered per send: "does this buffer pointer have a
 * registered slot index?". Implementations may build that answer from a
 * pre-allocated lookup ([StaticRegisteredBufferRegistry] / `LongObjectMap`),
 * from a chunk range tree (a future `DynamicRegisteredBufferRegistry`), or
 * always answer `-1` ([DisabledRegisteredBufferRegistry]).
 *
 * **Threading**: every method must run on the owning EventLoop pthread —
 * `io_uring_register_*` requires the ring's submitter task (`SINGLE_ISSUER`)
 * and the per-registry data structures are not synchronised. The constructor
 * is the exception: it allocates user-space state only and may run on the
 * thread that builds the engine. [initOnEventLoop] is where any
 * `io_uring_register_buffers` calls happen.
 */
@OptIn(ExperimentalForeignApi::class)
internal interface IoUringFixedBufferRegistry {

    /**
     * `true` once a kernel registration has succeeded and look-ups against
     * the registry can return non-`-1` slot indices.
     *
     * `false` for [DisabledRegisteredBufferRegistry] always, and for
     * [StaticRegisteredBufferRegistry] before [initOnEventLoop] or after
     * [close], or when the kernel registration failed.
     */
    val isActive: Boolean

    /**
     * Returns the registered slot index for [ptr], or `-1` when [ptr] is
     * not registered. The engine write path uses the return value as the
     * `bufIndex` of `IORING_OP_SEND_ZC_FIXED`; a `-1` triggers the
     * regular `SEND_ZC` (per-send page pinning) fallback at the call
     * site — this is the canonical pool-exhaustion / unpooled / disabled
     * path and does not need a separate signal.
     */
    fun indexOf(ptr: CPointer<ByteVar>): Int

    /**
     * Runs the kernel `io_uring_register_buffers` (or `..._sparse` for the
     * future dynamic variant) on the owning EventLoop pthread. No-op for
     * [DisabledRegisteredBufferRegistry].
     */
    fun initOnEventLoop()

    /**
     * Unregisters from the kernel and frees user-space state. Must run on
     * the owning EventLoop pthread. Wired via
     * [IoUringEventLoop.onExitHook] in [IoUringEventLoopGroup].
     */
    fun close()
}
