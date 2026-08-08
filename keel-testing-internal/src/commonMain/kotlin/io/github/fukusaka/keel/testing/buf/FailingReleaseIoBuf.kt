package io.github.fukusaka.keel.testing.buf

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.testing.InjectedFault

/**
 * An [IoBuf] whose first [release] throws instead of releasing.
 *
 * The seam for teardown paths that release a queue of buffers. Those loops are
 * written as `for (pw in pendingWrites) pw.buf.release()`, so what a failing
 * release costs is not one buffer but every buffer behind it, plus whatever the
 * caller was going to do on the next line. Without a buffer that can fail, that
 * whole class of teardown defect is only reachable by reading the code.
 *
 * **One-shot, and the underlying buffer is never lost.** [release] throws
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
 * **It is not a stand-in on the native read path.** Delegation covers [IoBuf],
 * and the pointer a transport reads through comes from an extension that casts
 * to a separate native-pointer interface — which this wrapper does not
 * implement, so that cast fails on it. Queue it as a pending write, where the
 * release is what the test is about.
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
