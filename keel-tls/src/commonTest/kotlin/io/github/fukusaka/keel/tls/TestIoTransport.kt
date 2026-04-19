package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * No-op [AbstractIoTransport] for TLS handler unit tests.
 *
 * Captures retained write buffers into [written] and tracks [flushed] /
 * [closed] flags so tests can assert on output and lifecycle without
 * plumbing a real socket.
 */
internal open class TestIoTransport : AbstractIoTransport(DefaultAllocator) {
    val written: MutableList<IoBuf> = mutableListOf()
    var flushed: Boolean = false
    var closed: Boolean = false

    override var readEnabled: Boolean = false
    override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined

    override fun write(buf: IoBuf) {
        buf.retain()
        written.add(buf)
    }

    override fun flush(): Boolean {
        flushed = true
        return true
    }

    override fun shutdownOutput() {}

    override fun close() {
        if (!markClosing()) return
        closed = true
    }
}
