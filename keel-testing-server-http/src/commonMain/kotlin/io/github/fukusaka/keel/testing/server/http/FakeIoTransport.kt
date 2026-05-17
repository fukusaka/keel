package io.github.fukusaka.keel.testing.server.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Minimal in-memory [AbstractIoTransport] used by [KeelHttpTestClient] to
 * drive an HTTP server pipeline with no engine and no socket.
 *
 * Outbound buffers are captured in [written] in arrival order so the test
 * client can parse the server's response off the wire. Buffers are taken
 * under the ownership-transfer contract of [AbstractIoTransport.write] —
 * this transport does not [IoBuf.retain] them; each captured entry is
 * released exactly once by [close].
 *
 * [ioDispatcher] is [Dispatchers.Unconfined], so a request coroutine the
 * pipeline launches runs inline within the `notifyRead` call that fed the
 * request — a full request/response round-trip completes synchronously,
 * with no wall-clock wait to bound.
 *
 * This is a deliberately minimal local fake: `keel-testing-internal`'s
 * `TestIoTransport` is a keel-internal fixture, not for a published
 * module, so `keel-testing-server-http` carries its own.
 */
internal class FakeIoTransport(
    allocator: BufferAllocator = DefaultAllocator,
) : AbstractIoTransport(allocator) {

    /** Outbound buffers captured by [write] in arrival order. */
    val written: MutableList<IoBuf> = mutableListOf()

    override var readEnabled: Boolean = false

    override val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Unconfined

    override fun write(buf: IoBuf) {
        // Ownership transferred from the caller per the AbstractIoTransport
        // contract: not retained here, released once by close().
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
