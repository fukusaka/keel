package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * No-op [AbstractIoTransport] for pipeline unit tests.
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
