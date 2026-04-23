package io.github.fukusaka.keel.pipeline

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * No-op [AbstractIoTransport] for pipeline unit tests.
 *
 * Under ownership-transfer semantics, `write(buf)` takes over the caller's
 * reference. We stash it into [written] so tests can assert on output; the
 * transport releases every entry at [close] time.
 */
internal open class TestIoTransport : AbstractIoTransport(DefaultAllocator) {
    val written: MutableList<IoBuf> = mutableListOf()

    override var readEnabled: Boolean = false
    override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined

    override fun write(buf: IoBuf) {
        // Ownership transferred from caller.
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
