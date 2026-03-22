package io.github.fukusaka.keel.engine.nwconnection

import kotlinx.coroutines.CancellableContinuation
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.resume

/**
 * Cancel-safe wrapper for a coroutine continuation passed to C callbacks via [StableRef].
 *
 * NWConnection's dispatch callbacks always fire — even after [nw_connection_cancel].
 * If a coroutine is cancelled before the callback runs, [invokeOnCancellation] must
 * NOT dispose the [StableRef]; otherwise the callback dereferences a disposed pointer
 * (use-after-dispose crash).
 *
 * **Ownership rule**: The [StableRef] is always disposed by the C callback, never by
 * [invokeOnCancellation]. Cancellation only sets the [cancelled] flag so the callback
 * skips [cont.resume].
 *
 * ```
 * Normal flow:
 *   C callback fires → tryResume(value) → cont.resume(value) → ref.dispose()
 *
 * Cancel flow:
 *   invokeOnCancellation → markCancelled()       (flag only, no dispose)
 *   C callback fires     → tryResume(value) skip → ref.dispose()
 * ```
 *
 * @param T The continuation result type.
 * @param cont The coroutine continuation to resume.
 */
internal class CallbackContext<T>(val cont: CancellableContinuation<T>) {
    private val completed = AtomicInt(0)

    /**
     * Attempts to resume the continuation with [value].
     *
     * Returns true if the continuation was resumed, false if it was
     * already cancelled or completed. Thread-safe via atomic CAS.
     */
    fun tryResume(value: T): Boolean {
        if (completed.compareAndSet(0, 1)) {
            cont.resume(value)
            return true
        }
        return false
    }

    /**
     * Marks the continuation as cancelled. Called from [invokeOnCancellation].
     *
     * Sets the flag so [tryResume] becomes a no-op when the C callback
     * eventually fires. Does NOT dispose the [StableRef].
     */
    fun markCancelled() {
        completed.value = 1
    }
}
