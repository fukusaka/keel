package io.github.fukusaka.keel.testing.buf

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.testing.InjectedFault

/**
 * An [IoBuf] whose [release] throws instead of releasing, [failures] times.
 *
 * The seam for teardown paths that release a queue of buffers. What a failing
 * release costs there is not one buffer: the loop stops, so every buffer behind
 * it stays queued, and so does whatever the caller was going to do on the next
 * line. Without a buffer that can fail, that whole class of teardown defect is
 * only reachable by reading the code.
 *
 * **It reaches the release loops, not the flush ones.** A flush takes a native
 * pointer off each queued buffer before it writes, and this wrapper cannot
 * supply one (see below), so a pending write it is part of fails there with a
 * cast error instead. The teardown drain — which takes no pointers — is what
 * this is for.
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
 * **It is not a stand-in wherever a native pointer is taken.** Delegation
 * covers [IoBuf], and the pointer a transport reads and writes through comes
 * from an extension that casts to a separate native-pointer interface — which
 * this wrapper does not implement, so that cast fails on it. Queue it as a
 * pending write **that is never flushed**, where the release is what the test
 * is about: a flush takes that pointer on the queued buffer and fails with a
 * cast error instead, which is not the failure under test.
 *
 * For the same reason `retain()` hands back the *wrapped* buffer, not this one,
 * so a caller that queues `buf.retain()` gets a release that succeeds.
 */
public class FailingReleaseIoBuf(
    private val delegate: IoBuf,
    failures: Int = 1,
) : IoBuf by delegate {

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
