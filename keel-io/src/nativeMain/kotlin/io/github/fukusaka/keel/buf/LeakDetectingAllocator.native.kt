@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.fukusaka.keel.buf

import kotlin.native.ref.createCleaner

/**
 * Native leak detection using [createCleaner].
 *
 * When the buffer object is garbage-collected without its memory owner being
 * invoked (i.e., release() was never called to reach refCount=0), the Cleaner
 * fires and invokes [onLeak] with the allocation site stack trace.
 *
 * **Detection timing**: the Cleaner fires during the next GC cycle after the
 * buffer becomes unreachable. In tests, [kotlin.native.runtime.GC.collect]
 * triggers this synchronously.
 *
 * **Cleaner constraints:**
 * - [LeakState] must NOT reference the buffer (circular reference prevents GC).
 * - [LeakState.released] is `@Volatile` because the Cleaner callback may run
 *   on a different thread than the EventLoop.
 * - The cleanup action captures only [LeakState], not the buffer itself.
 */
internal actual fun installLeakDetection(buf: IoBuf, onLeak: (String) -> Unit): IoBuf {
    val allocationSite = Exception("Buffer allocated here").stackTraceToString()
    val poolable = buf as PoolableIoBuf

    // State object that the Cleaner captures. Must not reference buf.
    val state = LeakState(allocationSite, onLeak)

    // Native Cleaner semantics: the cleanup block runs when the CLEANER
    // OBJECT becomes unreachable — not when some watched object does. The
    // cleaner must therefore live exactly as long as the buffer: the owner
    // decorator below (reachable through `buf.owner` for the buffer's whole
    // lifetime) retains it. An unretained cleaner is garbage immediately
    // and fires on the next GC cycle regardless of the buffer's liveness,
    // falsely reporting a leak whenever a GC lands between allocate() and
    // release() (observed as a rare full-suite flake where the allocation
    // pressure of unrelated tests makes that window real).
    val cleaner = createCleaner(state) { s ->
        if (!s.released) {
            s.onLeak("Unreleased buffer detected!\n${s.allocationSite}")
        }
    }

    // Decorate the owner to flip `state.released` before delegating to the
    // real release path. Avoids capturing `buf` inside the cleaner, and
    // anchors the cleaner's lifetime to the buffer's (see above).
    val originalOwner = poolable.owner
    poolable.owner = LeakDetectingOwner(cleaner, state, originalOwner)

    return buf
}

/**
 * Leak tracking state, separate from the buffer to avoid circular references.
 *
 * If the Cleaner captured the buffer directly, neither the buffer nor the
 * Cleaner would be GC'd, and the cleanup action would never fire.
 */
/**
 * Owner decorator that marks [state] released before the real release path
 * and — critically — holds the [cleaner] so it stays alive exactly as long
 * as the buffer that references this owner.
 */
private class LeakDetectingOwner(
    @Suppress("unused") private val cleaner: Any,
    private val state: LeakState,
    private val originalOwner: IoBufOwner,
) : IoBufOwner {
    override fun release(buf: IoBuf) {
        state.released = true
        originalOwner.release(buf)
    }
}

private class LeakState(
    val allocationSite: String,
    val onLeak: (String) -> Unit,
    @kotlin.concurrent.Volatile var released: Boolean = false,
)
