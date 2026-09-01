package io.github.fukusaka.keel.engine.nwconnection

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Classified outcome of an NWConnection receive callback, as routed
 * through `nwconnection.def`'s `keel_nw_dispatch_received` dual-path
 * helper.
 *
 * Replaces the previous flat tuple
 * `(zcHandle, zcPtr, bytesRead, isComplete, failed)` delivery, which
 * used correlated nullables (`zcHandle != null` iff `zcPtr != null`)
 * and forced [NwIoTransport.onReceiveCompletion] to perform a 4-way `when`
 * on raw flags. Bundling correlated state into one sealed value
 * follows the project convention "相関 nullable は 1 オブジェクトに
 * 束ねる" (avoid `!!` rule).
 *
 * The classification is performed in the C-callback boundary
 * (`NwIoTransport.readCallback`) so downstream handling sees only the
 * fully-typed variants and gets exhaustive `when` smart-casting.
 */
@OptIn(ExperimentalForeignApi::class)
internal sealed interface NwReceiveOutcome {
    /**
     * Single-region zero-copy delivery. [ptr] points into a region of
     * the retained `dispatch_data_t` identified by [handle]; the
     * region remains valid until
     * `nwconnection.keel_nw_dispatch_data_release` is called on
     * [handle].
     *
     * @property handle    Retained `dispatch_data_t` opaque pointer
     *                     (+1 via `__bridge_retained`).
     * @property ptr       Pointer to the region's first byte (valid
     *                     while [handle] is retained).
     * @property bytesRead Number of bytes in the region.
     */
    data class ZeroCopy(
        val handle: COpaquePointer,
        val ptr: CPointer<ByteVar>,
        val bytesRead: Int,
    ) : NwReceiveOutcome

    /**
     * Multi-region copy delivery. [bytesRead] bytes have already been
     * memcpy'd into the caller-supplied fallback buffer by the C
     * wrapper.
     */
    data class Copied(val bytesRead: Int) : NwReceiveOutcome

    /**
     * Peer closed / EOF (`is_complete` with zero bytes) or receive
     * failed. Caller signals `onReadClosed` and discards the fallback
     * buffer.
     *
     * [errno] is the POSIX errno of a failed receive (e.g. `ECONNRESET`),
     * or `0` for a clean EOF — so the caller can log the real reason and
     * distinguish an error-close from an orderly peer FIN.
     */
    data class Closed(val errno: Int) : NwReceiveOutcome

    /**
     * Spurious 0-byte completion without `is_complete`. Caller
     * recycles the fallback buffer and re-arms without delivering
     * anything to `onRead`.
     */
    data object Spurious : NwReceiveOutcome
}
