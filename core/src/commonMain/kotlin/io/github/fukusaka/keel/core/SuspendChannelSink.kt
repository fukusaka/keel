package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.io.SuspendSink

/**
 * Default [SuspendSink] implementation that delegates to [Channel.write]/[Channel.flush].
 *
 * Used by [Channel.asSuspendSink]'s default implementation. Engines can
 * override [Channel.asSuspendSink] to provide a specialized implementation
 * without changing this class.
 */
internal class SuspendChannelSink(private val channel: Channel) : SuspendSink {
    override suspend fun write(buf: IoBuf): Int = channel.write(buf)
    override suspend fun flush() = channel.flush()

    /** No-op: channel lifecycle is managed by the caller, not by this sink. */
    override fun close() {}
}
