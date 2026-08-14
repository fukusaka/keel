package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.posix.AF_INET
import platform.posix.EINTR
import platform.posix.SHUT_WR
import platform.posix.SOCK_STREAM
import platform.posix.errno
import platform.posix.shutdown
import platform.posix.socket
import posix_testing.keel_connect_inet_loopback
import posix_testing.keel_set_nosigpipe
import posix_testing.keel_set_rcvtimeo
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Test-only raw POSIX client I/O helpers.
 *
 * Engine integration tests verify server behavior against a "dumb" TCP
 * peer that bypasses the engine's [io.github.fukusaka.keel.core.Channel]
 * abstraction. Each engine module previously rolled its own
 * `rawConnect` / `rawWrite` / `rawRead` helpers that diverged in subtle
 * ways — partial writes silently truncated, short reads broke the loop
 * without distinguishing EOF / SO_RCVTIMEO / ECONNRESET, EINTR was not
 * retried. The EINTR bug surfaced in the long-standing
 * `IoUringPipelinedServerTest` GHA flake (PR #321 post-mortem); this
 * file centralizes the correct idiom.
 *
 * ## Layering
 *
 * All byte I/O routes through [NativeSocket] so the Layer 1 cinterop
 * wrappers (`keel_read` / `keel_write`) handle EINTR transparently.
 * This file holds only test-specific semantics: blocking loopback
 * connect, an absolute-monotonic-deadline read timeout, and utility
 * overloads for `String` payloads.
 *
 * ## Absolute deadline for [rawRead]
 *
 * `SO_RCVTIMEO` alone is insufficient under a busy signal rate: each
 * EINTR retry resets the kernel timer, so the worst-case `read`
 * duration is unbounded. [rawRead] therefore records an absolute
 * monotonic deadline, recomputes the remaining budget before every
 * retry, and re-applies `SO_RCVTIMEO` with the reduced value — a
 * signal storm cannot extend the bound beyond the caller's
 * requested [Duration].
 *
 * ## Test strategy
 *
 * No standalone self-test. Unlike the scripted fakes ([FakeNativeSocket] /
 * [FakeNativeSocketOps], which are pinned by their own contract tests), this
 * is a real-syscall helper with no in-memory invariant to break silently: its
 * correctness is the actual connect / read / write behaviour, which the ~20
 * engine integration tests that drive a server against it exercise directly —
 * a regression (truncated write, mis-handled EOF / timeout / EINTR) fails those
 * tests as a wrong server observation. A standalone test would only re-run the
 * same syscalls against a throwaway listener, duplicating that coverage.
 */
@OptIn(ExperimentalForeignApi::class)
public object PosixRawClient {

    private val socket: NativeSocket get() = PosixNativeSocket

    /**
     * Opens a blocking TCP socket connected to `127.0.0.1:[port]`
     * with `SO_RCVTIMEO` initialized to [timeout]. The caller owns
     * the returned fd and is responsible for [close].
     *
     * @throws IllegalStateException if `socket(2)` / `connect(2)` fails.
     */
    public fun rawConnect(port: Int, timeout: Duration = DEFAULT_TIMEOUT): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: ${errnoMessage(errno)}" }
        // Suppress SIGPIPE on this blocking client fd (macOS: SO_NOSIGPIPE).
        // Production sockets get this in PosixNativeSocketOps.setNonBlocking,
        // which this blocking client never calls; without it a rawWrite to a
        // peer the server has force-closed (e.g. by the write-idle timeout)
        // delivers SIGPIPE and kills the test process instead of failing EPIPE.
        keel_set_nosigpipe(fd)
        // Initial SO_RCVTIMEO value — rawRead recomputes it per retry
        // against an absolute deadline, but giving the initial call a
        // finite timeout prevents an accidental indefinite block from
        // misuse.
        val (initSec, initUsec) = timeout.toTimevalComponents()
        keel_set_rcvtimeo(fd, initSec, initUsec)
        val rc = keel_connect_inet_loopback(fd, port.toUShort())
        if (rc != 0) {
            val err = errno
            socket.close(fd)
            error("connect() failed: rc=$rc ${errnoMessage(err)}")
        }
        return fd
    }

    /**
     * Writes every byte of [data] to [fd]. Loops on partial writes;
     * EINTR is handled by Layer 1.
     *
     * @throws IllegalStateException on unrecoverable errno (`EPIPE` /
     *   `ECONNRESET` / etc.) or on a 0-byte return.
     */
    public fun rawWrite(fd: Int, data: ByteArray) {
        if (data.isEmpty()) return
        data.usePinned { pinned ->
            var total = 0
            while (total < data.size) {
                val ptr = pinned.addressOf(total)
                val result = socket.write(fd, ptr, data.size - total)
                when (result) {
                    is WriteResult.Written -> total += result.bytes
                    WriteResult.WouldBlock ->
                        error("write returned WouldBlock at $total/${data.size} — SO_SNDTIMEO?")
                    is WriteResult.Failed ->
                        error("write failed at $total/${data.size}: ${errnoMessage(result.errno)}")
                }
            }
        }
    }

    /** Convenience overload: writes [data] encoded as UTF-8. */
    public fun rawWrite(fd: Int, data: String): Unit = rawWrite(fd, data.encodeToByteArray())

    /**
     * Half-closes the write side of [fd] via `shutdown(SHUT_WR)`: a FIN is
     * sent to the peer (which then observes EOF on its read side) while
     * this client's read side stays open to receive a final response.
     */
    public fun rawShutdownWrite(fd: Int) {
        if (shutdown(fd, SHUT_WR) != 0) {
            error("shutdown(SHUT_WR) failed: ${errnoMessage(errno)}")
        }
    }

    /**
     * Reads exactly [size] bytes into a new [ByteArray]. Loops on
     * partial reads; EINTR is handled by Layer 1. Honors an absolute
     * monotonic deadline: every retry recomputes the remaining budget
     * and re-applies `SO_RCVTIMEO` so signal-storm-driven timer resets
     * cannot extend the total wait beyond [timeout].
     *
     * @throws IllegalStateException on EOF / errno failure / deadline
     *   expiry.
     */
    public fun rawReadBytes(fd: Int, size: Int, timeout: Duration = DEFAULT_TIMEOUT): ByteArray {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        val buf = ByteArray(size)
        buf.usePinned { pinned ->
            var total = 0
            while (total < size) {
                val remaining = deadline - TimeSource.Monotonic.markNow()
                if (remaining <= Duration.ZERO) {
                    error("read timed out after $total/$size bytes (deadline expired)")
                }
                // Re-arm SO_RCVTIMEO with the remaining budget so the
                // kernel timer bounds this single `read(2)` call and
                // can't be extended across retries by signal storms.
                val (sec, usec) = remaining.toTimevalComponents()
                keel_set_rcvtimeo(fd, sec, usec)

                val ptr = pinned.addressOf(total)
                val result = socket.read(fd, ptr, size - total)
                when (result) {
                    is ReadResult.Bytes -> total += result.bytes
                    ReadResult.Eof -> error("read returned EOF after $total/$size bytes")
                    ReadResult.WouldBlock -> {
                        // SO_RCVTIMEO fired for THIS chunk — go back to
                        // the loop head, where the deadline check will
                        // fail loudly (or let us retry briefly).
                        continue
                    }
                    is ReadResult.Failed -> {
                        val err = result.errno
                        // Defensive: Layer 1 retries EINTR internally, so we
                        // shouldn't see it here. Tolerate it if it slips
                        // through (e.g. a future kernel edge case).
                        if (err == EINTR) continue
                        error("read failed after $total/$size bytes: ${errnoMessage(err)}")
                    }
                }
            }
        }
        return buf
    }

    /** Convenience overload: returns the bytes decoded as UTF-8. */
    public fun rawRead(fd: Int, size: Int, timeout: Duration = DEFAULT_TIMEOUT): String =
        rawReadBytes(fd, size, timeout).decodeToString()

    /**
     * Reads up to [maxSize] bytes, returning whatever arrived before
     * the deadline, an EOF, or the `SO_RCVTIMEO` timer fires. A short
     * payload terminated by `EAGAIN` / `EWOULDBLOCK` is a valid
     * outcome — suited for HTTP-response-style reads where the exact
     * response length isn't known up front.
     *
     * **Costs the full [timeout] whenever the peer sends less than
     * [maxSize] and does not close.** There is no other way for this to
     * know the peer is done, so a caller that asks for 4 KiB of a
     * hundred-byte response waits out the timer on every call. When the
     * caller can recognise the end of what it wants, [rawReadUntil]
     * returns as soon as it arrives.
     */
    public fun rawReadUpTo(fd: Int, maxSize: Int, timeout: Duration = DEFAULT_TIMEOUT): String {
        val (sec, usec) = timeout.toTimevalComponents()
        keel_set_rcvtimeo(fd, sec, usec)

        val buf = ByteArray(maxSize)
        var total = 0
        buf.usePinned { pinned ->
            while (total < maxSize) {
                val ptr = pinned.addressOf(total)
                val result = socket.read(fd, ptr, maxSize - total)
                when (result) {
                    is ReadResult.Bytes -> total += result.bytes
                    ReadResult.Eof -> return@usePinned
                    ReadResult.WouldBlock -> return@usePinned // SO_RCVTIMEO fired — return what we have
                    is ReadResult.Failed -> {
                        val err = result.errno
                        if (err == EINTR) continue
                        error("read failed after $total/$maxSize bytes: ${errnoMessage(err)}")
                    }
                }
            }
        }
        return buf.decodeToString(0, total)
    }

    /**
     * Reads until [isComplete] accepts what has arrived, or until EOF,
     * the `SO_RCVTIMEO` timer, or [maxSize].
     *
     * The predicate is what makes this cheap: a caller that knows how
     * its payload ends stops as soon as it does, instead of waiting out
     * a timer that only tells it the peer went quiet. [timeout] stops
     * being a cost paid on success and becomes what bounds a failure --
     * per read, as `SO_RCVTIMEO` is, so a peer that dribbles can still
     * hold the call for longer than one [timeout].
     *
     * The predicate sees the whole payload so far, decoded, after every
     * read. Segmentation is the kernel's to choose, so a character can
     * arrive split across two reads -- and that is safe: the decode
     * substitutes U+FFFD rather than throwing, so a split defers the
     * predicate instead of firing it on a half-read payload, and the
     * next decode sees the character whole. Measured, not assumed.
     */
    public fun rawReadUntil(
        fd: Int,
        maxSize: Int,
        timeout: Duration = DEFAULT_TIMEOUT,
        isComplete: (String) -> Boolean,
    ): String {
        val (sec, usec) = timeout.toTimevalComponents()
        keel_set_rcvtimeo(fd, sec, usec)

        val buf = ByteArray(maxSize)
        var total = 0
        buf.usePinned { pinned ->
            while (total < maxSize) {
                val ptr = pinned.addressOf(total)
                when (val result = socket.read(fd, ptr, maxSize - total)) {
                    is ReadResult.Bytes -> {
                        total += result.bytes
                        if (isComplete(buf.decodeToString(0, total))) return@usePinned
                    }
                    ReadResult.Eof -> return@usePinned
                    ReadResult.WouldBlock -> return@usePinned // SO_RCVTIMEO fired — return what we have
                    is ReadResult.Failed -> {
                        val err = result.errno
                        if (err == EINTR) continue
                        error("read failed after $total/$maxSize bytes: ${errnoMessage(err)}")
                    }
                }
            }
        }
        return buf.decodeToString(0, total)
    }

    /**
     * Issues a single `read(2)` call for up to [size] bytes and
     * returns the [ReadResult] verbatim. Useful for
     * `shutdownOutput`-style EOF assertions.
     */
    public fun rawReadOnce(fd: Int, size: Int, timeout: Duration = DEFAULT_TIMEOUT): ReadResult {
        val (sec, usec) = timeout.toTimevalComponents()
        keel_set_rcvtimeo(fd, sec, usec)

        val buf = ByteArray(size)
        return buf.usePinned { pinned ->
            socket.read(fd, pinned.addressOf(0), size)
        }
    }

    /** Default SO_RCVTIMEO / deadline for all read helpers. */
    public val DEFAULT_TIMEOUT: Duration = 5.seconds

    private fun Duration.toTimevalComponents(): Pair<Long, Long> {
        val totalUs = inWholeMicroseconds.coerceAtLeast(0)
        val sec = totalUs / 1_000_000L
        val usec = totalUs % 1_000_000L
        return sec to usec
    }
}
