package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * No-op [AbstractIoTransport] for TLS handler unit tests.
 *
 * Under ownership-transfer semantics, `write(buf)` takes over the caller's
 * reference. We stash it into [written] so tests can inspect the bytes and
 * then release them (test is the final owner of the captured list).
 */
internal open class TestIoTransport : AbstractIoTransport(DefaultAllocator) {
    val written: MutableList<IoBuf> = mutableListOf()
    var flushed: Boolean = false
    var closed: Boolean = false

    override var readEnabled: Boolean = false
    override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined

    override fun write(buf: IoBuf) {
        // Ownership transferred from caller. Test owns the captured ref until
        // it releases each entry in `written`.
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
