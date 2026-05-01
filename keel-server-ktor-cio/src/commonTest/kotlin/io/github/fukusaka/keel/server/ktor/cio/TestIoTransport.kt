package io.github.fukusaka.keel.server.ktor.cio

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * No-op [AbstractIoTransport] for unit tests in this module.  Same shape
 * as the per-module `TestIoTransport` used by `keel-core`,
 * `keel-codec-http`, and `keel-tls` test sources.
 */
internal open class TestIoTransport : AbstractIoTransport(DefaultAllocator) {
    val written: MutableList<IoBuf> = mutableListOf()

    override var readEnabled: Boolean = false
    override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined

    override fun write(buf: IoBuf) {
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
