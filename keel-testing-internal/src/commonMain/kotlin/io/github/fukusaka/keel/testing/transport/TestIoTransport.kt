package io.github.fukusaka.keel.testing.transport

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.AbstractIoTransport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Test-only no-op [AbstractIoTransport] for pipeline / codec / engine seam
 * tests. Captures every outbound [IoBuf] in [written] so a test can assert on
 * the encoded byte sequence without plumbing a real socket.
 *
 * **Ownership-transfer semantics** — matches the contract documented on
 * [AbstractIoTransport.write]:
 *
 * > Buffers `buf` for the next `flush` call under ownership-transfer
 * > semantics: the transport takes over the caller's reference and releases
 * > it after the buffer has been flushed (or the transport is torn down).
 * > The caller must not touch `buf` after this call returns.
 *
 * This canonical implementation does NOT retain the buf inside [write]. The
 * captured entries in [written] sit at the same refcount the caller created
 * them at, and they are released exactly once on [close] (which also clears
 * [written]). Two earlier copies of this class — in `keel-codec-http` and
 * `keel-codec-websocket` `commonTest` — used `retain` semantics that
 * inflated the refcount and effectively leaked one ref per write under
 * `TrackingAllocator`. The leak was invisible to the codec tests (they
 * never asserted refcount balance) but violated the contract; consolidation
 * fixes it.
 *
 * **Use with `TrackingAllocator`** — pass an explicit [allocator] (typically
 * `TrackingAllocator(DefaultAllocator)` from `keel-io`) to drive IoBuf-leak
 * regression checks where `outstandingCount` must be 0 after teardown:
 *
 * ```kotlin
 * val tracker = TrackingAllocator(DefaultAllocator)
 * val transport = TestIoTransport(tracker)
 * // ... drive pipeline events ...
 * transport.close()
 * assertEquals(0, tracker.outstandingCount)
 * ```
 *
 * **State flags** — [flushed] and [closed] mirror lifecycle observability
 * needs from `keel-tls`-style tests where the assertion is "transport
 * observed a flush" rather than "produced specific bytes". Both are
 * read-only public properties (`private set`) so subclass code or call
 * sites cannot accidentally desync the flag from the actual lifecycle
 * state — read them, do not assign them.
 *
 * **Manual release pattern** — for tests that want to inspect [written]
 * across multiple assertions without dragging in `close()`'s lifecycle
 * state-machine, [releaseWritten] releases all captured entries and clears
 * the list without driving [AbstractIoTransport]'s `markClosing` /
 * `markTeardownStarted` flow.
 *
 * **Threading** — [ioDispatcher] returns [Dispatchers.Unconfined] so any
 * coroutine launched on the transport's dispatcher runs inline on the
 * caller's thread; tests do not need a real EventLoop.
 *
 * **History (consolidation)** — six per-module copies accumulated before
 * this canonical version landed:
 *
 * - `keel-core/src/commonTest/.../pipeline/TestIoTransport.kt` (ownership-transfer)
 * - `keel-tls/src/commonTest/.../tls/TestIoTransport.kt`
 *   (ownership-transfer + manual-release pattern, with `flushed` / `closed`
 *   flags — adopted into the canonical class)
 * - `keel-codec-http/src/commonTest/.../codec/http/TestIoTransport.kt`
 *   (retain semantics — fixed by consolidation)
 * - `keel-codec-websocket/src/commonTest/.../codec/websocket/TestIoTransport.kt`
 *   (retain semantics — fixed by consolidation)
 * - `keel-server-ktor-cio/src/commonTest/.../server/ktor/cio/TestIoTransport.kt`
 *   (ownership-transfer)
 * - `keel-engine-netty/src/jvmTest/.../engine/netty/TestIoTransport.kt`
 *   (ownership-transfer + `BufferAllocator` parameter — adopted into the
 *   canonical class; this is what enabled the IoBuf-leak seam tests in PR #485)
 *
 * @param allocator buffer allocator forwarded to [AbstractIoTransport]'s
 *   constructor. Default [DefaultAllocator] keeps existing call sites
 *   unchanged; pass a `TrackingAllocator` for refcount-balance assertions.
 */
public open class TestIoTransport(
    allocator: BufferAllocator = DefaultAllocator,
) : AbstractIoTransport(allocator) {
    /**
     * Outbound buffers captured by [write] in arrival order. Tests inspect
     * these directly (`written[0].readString()`, etc.). Each entry is
     * released exactly once on [close] or [releaseWritten].
     */
    public val written: MutableList<IoBuf> = mutableListOf()

    /** `true` once [flush] has been called at least once. */
    public var flushed: Boolean = false
        private set

    /** `true` once [close] has been called (idempotent — stays `true`). */
    public var closed: Boolean = false
        private set

    override var readEnabled: Boolean = false

    /** Number of [pauseReads] calls observed; pins flow-control wiring in tests. */
    public var pauseReadsCount: Int = 0
        private set

    /** Number of [resumeReads] calls observed; pins flow-control wiring in tests. */
    public var resumeReadsCount: Int = 0
        private set

    override fun pauseReads() {
        pauseReadsCount++
        readEnabled = false
    }

    override fun resumeReads() {
        resumeReadsCount++
        readEnabled = true
    }

    /**
     * Backs [ioDispatcher]. Replaceable because a test that flips
     * [owningContext] to `false` needs a dispatcher that accepts `dispatch` —
     * the default [Dispatchers.Unconfined] rejects it.
     */
    public var dispatcher: CoroutineDispatcher = Dispatchers.Unconfined

    override val ioDispatcher: CoroutineDispatcher get() = dispatcher

    /**
     * Reported by [inOwningContext]. Flip to `false` to make the pipeline take
     * its off-context branch. Set [dispatcher] to something that accepts
     * `dispatch` first — the default [Dispatchers.Unconfined] rejects it, so a
     * `false` [owningContext] on the default dispatcher throws from the
     * pipeline's dispatch call.
     */
    public var owningContext: Boolean = true

    override val inOwningContext: Boolean get() = owningContext

    /**
     * Reported by [canDispatchToOwningContext]. Flip to `false` to model the
     * state no other knob reaches: the transport is still open, but its owning
     * context has stopped, so a dispatch would be accepted and never run.
     */
    public var owningContextAlive: Boolean = true

    override val canDispatchToOwningContext: Boolean get() = owningContextAlive

    override fun write(buf: IoBuf) {
        // Ownership transferred from caller per AbstractIoTransport.write
        // contract. We do NOT retain — the buf sits in `written` at the
        // same refcount the caller had, and gets released exactly once
        // by close() / releaseWritten().
        written.add(buf)
    }

    override fun flush(): Boolean {
        flushed = true
        return true
    }

    override fun shutdownOutput() {}

    /**
     * No-op: this double captures writes in [written] rather than sending
     * them, so there is no FIN to order against buffered output.
     */
    override fun sendFin() {}

    override fun close() {
        if (!markClosing()) return
        if (!markTeardownStarted()) return
        closed = true
        for (buf in written) buf.release()
        written.clear()
    }

    /**
     * Release every captured outbound buffer and clear [written]. Useful
     * from test teardown when the test wants alloc/release counts balanced
     * but does not want to drive the [AbstractIoTransport] lifecycle state
     * machine (which [close] does).
     */
    public fun releaseWritten() {
        for (buf in written) buf.release()
        written.clear()
    }
}
