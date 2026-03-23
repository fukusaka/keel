package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.io.NativeBuf
import io.github.fukusaka.keel.io.SuspendSource

/**
 * Default [SuspendSource] implementation that delegates to [Channel.read].
 *
 * Used by [Channel.asSuspendSource]'s default implementation. Engines can
 * override [Channel.asSuspendSource] to provide a specialized implementation
 * (e.g., io_uring completion-based reads) without changing this class.
 */
internal class SuspendChannelSource(private val channel: Channel) : SuspendSource {
    override suspend fun read(buf: NativeBuf): Int = channel.read(buf)

    /** No-op: channel lifecycle is managed by the caller, not by this source. */
    override fun close() {}
}
