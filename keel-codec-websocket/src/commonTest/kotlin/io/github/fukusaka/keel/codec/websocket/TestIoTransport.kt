package io.github.fukusaka.keel.codec.websocket

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * No-op [AbstractIoTransport] for pipeline / codec unit tests.
 *
 * Captures write buffers into [written] so tests can assert on output
 * without plumbing a real socket. [flush] returns `true` synchronously
 * so `awaitPendingFlush` also resolves immediately.
 */
internal open class TestIoTransport : AbstractIoTransport(DefaultAllocator) {
    val written: MutableList<IoBuf> = mutableListOf()

    override var readEnabled: Boolean = false
    override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined

    override fun write(buf: IoBuf) {
        // Retain and record; do not advance readerIndex so tests can inspect.
        buf.retain()
        written.add(buf)
    }

    override fun flush(): Boolean = true
    override fun shutdownOutput() {}

    override fun close() {
        if (!markClosing()) return
        if (!markTeardownStarted()) return
        for (buf in written) buf.release()
        written.clear()
    }
}
