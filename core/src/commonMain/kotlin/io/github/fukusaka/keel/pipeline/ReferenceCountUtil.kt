package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Utility for safe reference count management in the pipeline.
 *
 * Used by [TailHandler] and [TypedChannelInboundHandler] to release
 * messages that implement [IoBuf] without risking double-release.
 */
internal object ReferenceCountUtil {

    /**
     * Releases [msg] if it is an [IoBuf].
     *
     * Safe to call even if the buffer has already been released —
     * catches [IllegalStateException] from double-release.
     */
    fun safeRelease(msg: Any) {
        if (msg is IoBuf) {
            try {
                msg.release()
            } catch (_: IllegalStateException) {
                // Already released — typically indicates a double-release bug
                // in a handler. Silently ignored here because the pipeline's
                // safety net should not throw; the root cause should be fixed
                // in the handler itself.
            }
        }
    }
}
