package io.github.fukusaka.keel.buf

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue

/**
 * JVM leak detection using [PhantomReference] + [ReferenceQueue].
 *
 * When the buffer object is garbage-collected, its PhantomReference is
 * enqueued in the ReferenceQueue. A check drains the queue and reports
 * any buffers that were GC'd without being released.
 *
 * Unlike Native's Cleaner (which fires automatically), JVM requires
 * explicit polling of the ReferenceQueue. [LeakDetectingAllocator]
 * checks on each allocation, so leaked buffers are reported when the
 * next buffer is allocated.
 */
internal actual fun installLeakDetection(buf: IoBuf, onLeak: (String) -> Unit): IoBuf {
    val allocationSite = Exception("Buffer allocated here").stackTraceToString()
    val poolable = buf as PoolableIoBuf
    val originalDeallocator = poolable.deallocator

    val entry = LeakEntry(allocationSite, onLeak)
    val ref = PhantomReference<IoBuf>(buf, leakQueue)
    leakEntries[ref as PhantomReference<*>] = entry

    poolable.deallocator = { b ->
        entry.released = true
        leakEntries.remove(ref as PhantomReference<*>)
        ref.clear()
        originalDeallocator?.invoke(b) ?: b.close()
    }

    // Drain queue on each allocation to report previously leaked buffers.
    drainLeakQueue()

    return buf
}

/** Shared ReferenceQueue for all leak-detected buffers. */
private val leakQueue = ReferenceQueue<IoBuf>()

/** Map from PhantomReference to leak metadata. */
private val leakEntries = java.util.concurrent.ConcurrentHashMap<PhantomReference<*>, LeakEntry>()

/**
 * Drains the ReferenceQueue and reports any leaked buffers.
 *
 * Called on each allocation so leak reports appear promptly.
 */
private fun drainLeakQueue() {
    while (true) {
        val ref = leakQueue.poll() ?: break
        val entry = leakEntries.remove(ref as PhantomReference<*>)
        if (entry != null && !entry.released) {
            entry.onLeak("Unreleased buffer detected!\n${entry.allocationSite}")
        }
    }
}

private class LeakEntry(
    val allocationSite: String,
    val onLeak: (String) -> Unit,
    @Volatile var released: Boolean = false,
)
