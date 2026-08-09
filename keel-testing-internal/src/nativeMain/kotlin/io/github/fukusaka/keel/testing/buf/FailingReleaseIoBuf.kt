package io.github.fukusaka.keel.testing.buf

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.NativePointerAccess
import io.github.fukusaka.keel.buf.UnsafeIoBufApi
import io.github.fukusaka.keel.buf.unsafePointer
import io.github.fukusaka.keel.testing.InjectedFault
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * An [IoBuf] whose [release] throws instead of releasing, [failures] times.
 *
 * The seam for teardown paths that release a queue of buffers. What a failing
 * release costs there is more than one buffer, and how much more depends on the
 * drain. Where the walk stops at the refusal, everything behind it stays queued
 * — and so does whatever the caller was going to do on the next line. The
 * shared drain the POSIX teardowns use walks to the end now, so what it loses
 * is one buffer per refusal rather than the tail; the drains that still release
 * inline as they go, and every transport with its own copy, keep the older
 * shape. Without a buffer that can fail, none of that is reachable from a test.
 *
 * **It reaches the flush loops as well as the teardown drain.** A flush takes a
 * native pointer off each queued buffer before it writes, so this forwards
 * [unsafePointer] to the wrapped buffer — without that, a pending write it was
 * part of failed on the cast long before any release, and the release loop
 * inside the flush was unreachable. That loop is where a failed release is at
 * its most expensive, because a *later* pass over the same queue is what pays
 * for it.
 *
 * **Bounded, and the underlying buffer is never lost.** [release] throws
 * [InjectedFault] the first [failures] times and delegates afterwards, so a
 * test can let the production path fail, observe what that cost, and then clean
 * up through [releaseUnderlying] — which is what keeps a `TrackingAllocator`
 * assertion in the same test meaningful. A buffer that simply leaked would make
 * every leak count in that test unreadable.
 *
 * **[close] refuses on the same counter.** `IoBuf` declares it separately from
 * [release] and documents it as "an escape for engine shutdown / emergency
 * teardown scenarios" — which is this seam's whole subject. A teardown written
 * against `close()` would otherwise take this buffer, release it cleanly, and
 * leave a test asserting what a failing release costs green against a build
 * that never fixed it.
 *
 * Everything else delegates, so this is the buffer the transport actually
 * queued: same bytes, same indices, same reader and writer positions.
 *
 * Note that `retain()` is delegated and hands back the *wrapped* buffer, not
 * this one, so a caller that queues `buf.retain()` gets a release that
 * succeeds.
 *
 * **Native source set, because forwarding the pointer requires an interface
 * that only exists there.** The io_uring and NWConnection transports carry the
 * same release-then-clear drain this seam is for and can use this today — both
 * test source sets already depend on this module. The Netty, NIO and Node ones
 * cannot, and reaching those means giving this a common form: the refusal and
 * its counter in common code, the pointer in a native subclass.
 */
@OptIn(ExperimentalForeignApi::class)
public class FailingReleaseIoBuf(
    private val delegate: IoBuf,
    failures: Int = 1,
) : IoBuf by delegate, NativePointerAccess {

    /**
     * The wrapped buffer's own pointer.
     *
     * Delegation carries [IoBuf] and nothing else, so without this the cast
     * behind `IoBuf.unsafePointer` fails on this wrapper and no flush can reach
     * the release it is standing in for.
     */
    @UnsafeIoBufApi
    override val unsafePointer: CPointer<ByteVar> get() = delegate.unsafePointer

    private var remaining = failures

    /** How many [release] or [close] calls have been refused so far. */
    public var refusedReleases: Int = 0
        private set

    override fun release(): Boolean {
        if (remaining > 0) {
            remaining--
            refusedReleases++
            throw InjectedFault("release refused by FailingReleaseIoBuf")
        }
        return delegate.release()
    }

    override fun close() {
        if (remaining > 0) {
            remaining--
            refusedReleases++
            throw InjectedFault("close refused by FailingReleaseIoBuf")
        }
        delegate.close()
    }

    /**
     * Releases the wrapped buffer, whatever [release] or [close] has refused
     * so far.
     *
     * For the test's own cleanup: the production path under test is expected to
     * have failed, so nobody else is going to release this.
     */
    public fun releaseUnderlying(): Boolean {
        remaining = 0
        return delegate.release()
    }
}
