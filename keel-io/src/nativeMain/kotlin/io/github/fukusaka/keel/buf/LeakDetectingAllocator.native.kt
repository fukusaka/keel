@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.fukusaka.keel.buf

import kotlin.native.ref.createCleaner

/**
 * Native leak detection using [createCleaner].
 *
 * When the buffer object is garbage-collected without its deallocator being
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
    val originalDeallocator = poolable.deallocator

    // State object that the Cleaner captures. Must not reference buf.
    val state = LeakState(allocationSite, onLeak)

    // Cleaner attached to the buffer. When buf is GC'd, if state.released
    // is still false, the buffer was leaked.
    createCleaner(state) { s ->
        if (!s.released) {
            s.onLeak("Unreleased buffer detected!\n${s.allocationSite}")
        }
    }

    // Intercept deallocator to mark as released before pool return / close.
    poolable.deallocator = { b ->
        state.released = true
        originalDeallocator?.invoke(b) ?: b.close()
    }

    return buf
}

/**
 * Leak tracking state, separate from the buffer to avoid circular references.
 *
 * If the Cleaner captured the buffer directly, neither the buffer nor the
 * Cleaner would be GC'd, and the cleanup action would never fire.
 */
private class LeakState(
    val allocationSite: String,
    val onLeak: (String) -> Unit,
    @kotlin.concurrent.Volatile var released: Boolean = false,
)
