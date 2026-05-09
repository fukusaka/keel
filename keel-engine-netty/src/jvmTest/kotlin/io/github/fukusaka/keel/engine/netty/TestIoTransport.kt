package io.github.fukusaka.keel.engine.netty

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * No-op [AbstractIoTransport] for engine-netty pipeline / codec seam tests.
 *
 * Captures every outbound [IoBuf] into [written] (with a [retain] call so the
 * buffer survives until the test asserts on it) so tests can verify pipeline
 * output without plumbing a real socket. [flush] returns `true` synchronously
 * so `awaitPendingFlush` resolves immediately.
 *
 * Mirror of `keel-codec-websocket` and `keel-codec-http`'s `TestIoTransport`
 * — duplicated here because engine-netty's jvmTest cannot reach those modules'
 * `commonTest` source sets directly. If a shared `keel-test-fixtures` source
 * set is introduced later, the duplicates collapse into it.
 *
 * Pass an explicit [allocator] (typically a `TrackingAllocator(DefaultAllocator)`)
 * to drive K4-class regression checks where alloc/release counts must match.
 */
internal open class TestIoTransport(
    allocator: BufferAllocator = DefaultAllocator,
) : AbstractIoTransport(allocator) {
    val written: MutableList<IoBuf> = mutableListOf()

    override var readEnabled: Boolean = false
    override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined

    override fun write(buf: IoBuf) {
        // Take ownership: transport.write transfers ownership from the caller
        // (encoder / pipeline) to the transport, and a real transport would
        // release the buffer after the socket write completes. The test
        // stores the buffer in [written] for assertion and releases it
        // exactly once via [releaseWritten] / [close], matching the real
        // transport's net refcount delta of -1 per write.
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

    /**
     * Releases every captured outbound buffer and clears [written]. Call this from
     * test teardown to ensure that the [io.github.fukusaka.keel.buf.TrackingAllocator]
     * counts are balanced regardless of whether the test asserted on every output.
     */
    fun releaseWritten() {
        for (buf in written) buf.release()
        written.clear()
    }
}
