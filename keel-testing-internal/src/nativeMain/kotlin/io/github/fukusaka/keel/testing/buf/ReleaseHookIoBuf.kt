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
 * A [FailingReleaseIoBuf] variant whose first refusal runs [beforeRefusal]
 * first — the seam for a state change that races the drain from another
 * thread, observed at the one point mid-drain where production code calls
 * out of the flush loop.
 *
 * A drain that dequeues an entry and then has its release refused is how a
 * test reaches "the queue emptied but the frame threw before any report";
 * the hook lets that same instant also carry the racing event (an off-loop
 * `close()` flipping the transport's flag) whose interleaving the test
 * pins. Bounded exactly like [FailingReleaseIoBuf]: one refusal, then
 * delegation, with [releaseUnderlying] for the test's own cleanup.
 *
 * A hook that throws refuses the release with its own throwable instead —
 * the refusal below is then unreachable. The bound still holds: the flag is
 * set before the hook runs, so the second call delegates either way. That is
 * how a test reaches a release refused with an instance it holds, whose
 * suppressed graph it means to assert on.
 */
@OptIn(ExperimentalForeignApi::class)
public class ReleaseHookIoBuf(
    private val delegate: IoBuf,
    private val beforeRefusal: () -> Unit,
) : IoBuf by delegate, NativePointerAccess {

    /** The wrapped buffer's own pointer, for the same reason [FailingReleaseIoBuf] forwards it. */
    @UnsafeIoBufApi
    override val unsafePointer: CPointer<ByteVar> get() = delegate.unsafePointer

    private var refused = false

    override fun release(): Boolean {
        if (!refused) {
            refused = true
            beforeRefusal()
            throw InjectedFault("release refused by ReleaseHookIoBuf")
        }
        return delegate.release()
    }

    override fun close() {
        release()
    }

    /** Releases the wrapped buffer; for the test's own cleanup after the refusal. */
    public fun releaseUnderlying(): Boolean = delegate.release()
}
