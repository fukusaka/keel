package io.github.fukusaka.keel.core

import io.github.fukusaka.keel.io.PushSuspendSource
import io.github.fukusaka.keel.io.PushToSuspendSourceAdapter
import io.github.fukusaka.keel.io.SuspendSource

/**
 * Marker interface for a [Channel] whose read side delivers engine-owned
 * buffers (push model).
 *
 * A class implementing [PushChannel] MUST also implement [Channel].
 * The write side, lifecycle, and dispatchers are provided by [Channel].
 * [PushChannel] adds only [asPushSuspendSource] for zero-copy reading.
 *
 * Used by engines where the kernel or runtime fills its own buffer before
 * the application requests data: io_uring multishot recv, Netty channelRead,
 * NWConnection receive, Node.js socket.on('data').
 *
 * The caller receives ownership of the [io.github.fukusaka.keel.buf.IoBuf]
 * from [PushSuspendSource.readOwned] and MUST call
 * [io.github.fukusaka.keel.buf.IoBuf.release] when done reading.
 *
 * **Design note — MemoryOwner abstraction rejected**: A `MemoryOwner<IoBuf>`
 * wrapper was considered but rejected because [io.github.fukusaka.keel.buf.IoBuf]
 * already provides retain/release with deallocator callback for ownership
 * management. Adding a wrapper would introduce indirect access overhead
 * (`owner.value.readByte()`) on the hot path without meaningful type safety
 * gain. Revisit if non-IoBuf memory ownership is needed (YAGNI).
 * See design.md §4.7.
 *
 * **Write path unchanged**: Write is inherently push (app provides data to
 * engine). The existing [Channel.write]/[Channel.flush] with deferred flush
 * batching is already optimal. No push-model abstraction needed for writes.
 *
 * ```
 * Pull model (Channel):     App provides IoBuf → Channel.read(buf) → kernel writes into buf
 * Push model (PushChannel):  Kernel writes into engine buffer → readOwned() → App receives IoBuf
 * ```
 *
 * @see Channel for pull-model read and all write/lifecycle methods
 * @see PushSuspendSource for the push-model read API
 * @see PushServerChannel for the server-side counterpart
 */
interface PushChannel {

    /**
     * Returns a [PushSuspendSource] for zero-copy reading.
     *
     * The returned source delivers engine-owned
     * [IoBuf][io.github.fukusaka.keel.buf.IoBuf] instances directly.
     * Use with [io.github.fukusaka.keel.io.BufferedSuspendSource] push-mode
     * constructor (future) for zero-copy codec integration.
     */
    fun asPushSuspendSource(): PushSuspendSource

    /**
     * Returns a pull-model [SuspendSource] by wrapping [asPushSuspendSource]
     * with [PushToSuspendSourceAdapter].
     *
     * This involves one [io.github.fukusaka.keel.buf.IoBuf.copyTo] per read.
     * For zero-copy, use [asPushSuspendSource] directly with
     * BufferedSuspendSource push-mode.
     *
     * Overrides [Channel.asSuspendSource] when both interfaces are implemented.
     */
    fun asSuspendSource(): SuspendSource =
        PushToSuspendSourceAdapter(asPushSuspendSource())
}
