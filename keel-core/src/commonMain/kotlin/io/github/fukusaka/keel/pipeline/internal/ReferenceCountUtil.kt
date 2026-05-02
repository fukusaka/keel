package io.github.fukusaka.keel.pipeline.internal

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Utility for safe reference count management in the pipeline.
 *
 * Used by [TailHandler] and [io.github.fukusaka.keel.pipeline.TypedInboundHandler]
 * to release messages that implement [IoBuf] without risking double-release,
 * and by [DefaultPipeline] to reclaim [AutoCloseable] message wrappers (such as
 * `HttpBody`) that own an [IoBuf] but are not themselves [IoBuf] instances.
 */
internal object ReferenceCountUtil {

    /**
     * Releases [msg] if it carries an owned resource.
     *
     * - If [msg] is an [IoBuf], calls [IoBuf.release].
     * - If [msg] is an [AutoCloseable] (but not an [IoBuf]), calls [AutoCloseable.close].
     *
     * Safe to call even if the resource has already been released —
     * catches [IllegalStateException] from double-release. Silently ignored
     * because the pipeline safety net must not throw; the root cause should
     * be fixed in the handler itself.
     */
    fun safeRelease(msg: Any) {
        when (msg) {
            is IoBuf -> try { msg.release() } catch (_: IllegalStateException) {}
            is AutoCloseable -> try { msg.close() } catch (_: IllegalStateException) {}
        }
    }
}
