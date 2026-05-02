package io.github.fukusaka.keel.pipeline.internal

import io.github.fukusaka.keel.buf.Releasable

/**
 * Utility for safe reference count management in the pipeline.
 *
 * Used by [TailHandler] and [io.github.fukusaka.keel.pipeline.TypedInboundHandler]
 * to release messages that implement [Releasable] (raw [io.github.fukusaka.keel.buf.IoBuf]
 * buffers and [Releasable] wrappers such as `HttpBody`) without risking double-release.
 */
internal object ReferenceCountUtil {

    /**
     * Releases [msg] if it implements [Releasable].
     *
     * Safe to call even if the resource has already been released —
     * catches [IllegalStateException] from double-release. Silently ignored
     * because the pipeline safety net must not throw; the root cause should
     * be fixed in the handler itself.
     */
    fun safeRelease(msg: Any) {
        if (msg is Releasable) {
            try { msg.release() } catch (_: IllegalStateException) {}
        }
    }
}
