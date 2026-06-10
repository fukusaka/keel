package io.github.fukusaka.keel.engine.iouring

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * [IoUringFixedBufferRegistry] used when [RegisteredBufferStrategy.DISABLED]
 * is selected, or when [RegisteredBufferStrategy.STATIC] auto-falls back
 * because the kernel lacks `IORING_REGISTER_BUFFERS`.
 *
 * Stateless object: every [indexOf] returns `-1`, so the write path picks
 * the regular `SEND_ZC` (per-send page pinning) branch on every send.
 * [isActive] is `false`, [initOnEventLoop] and [close] are no-ops.
 */
@OptIn(ExperimentalForeignApi::class)
internal object DisabledRegisteredBufferRegistry : IoUringFixedBufferRegistry {
    override val isActive: Boolean get() = false
    override fun indexOf(ptr: CPointer<ByteVar>): Int = -1
    override fun initOnEventLoop() = Unit
    override fun close() = Unit
}
