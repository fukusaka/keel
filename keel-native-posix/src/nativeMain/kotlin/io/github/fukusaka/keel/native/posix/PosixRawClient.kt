package io.github.fukusaka.keel.native.posix

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.AF_INET
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.errno
import platform.posix.read
import platform.posix.socket
import platform.posix.write
import posix_socket.keel_connect_inet_loopback
import posix_socket.keel_set_rcvtimeo

/**
 * Test-only raw POSIX client I/O helpers.
 *
 * Engine tests verify server behavior against a "dumb" TCP peer that
 * bypasses the [io.github.fukusaka.keel.core.Channel] abstraction. Each
 * engine module previously rolled its own `rawConnect` / `rawWrite` /
 * `rawRead` helpers that diverged in subtle ways:
 *
 * - Partial writes silently truncated (`write()` called once with no loop).
 * - Partial reads silently ended the read loop on `n <= 0` (masking EOF
 *   and errors as a short payload).
 * - EINTR was not retried, which under CPU contention on GHA 4-vCPU
 *   runners surfaced as the long-standing `IoUringPipelinedServerTest`
 *   flake (Kotlin/Native runtime signals interrupt blocking syscalls;
 *   see PR #321 post-mortem).
 *
 * This file is the single source of truth for the raw-client idiom,
 * centralising:
 *
 * - EINTR retry on every blocking syscall
 * - Full-payload read/write loops that surface the real errno on failure
 * - A 5-second `SO_RCVTIMEO` default so stuck tests fail loudly instead
 *   of hanging forever
 *
 * **Not for production use.** Production code should use the engine's
 * [io.github.fukusaka.keel.core.StreamEngine.connect] API, which goes
 * through the same I/O path as the server side and exercises the full
 * Pipeline / read-ahead machinery.
 */
@OptIn(ExperimentalForeignApi::class)
public object PosixRawClient {

    /**
     * Creates a blocking TCP socket connected to `127.0.0.1:port` with
     * `SO_RCVTIMEO` set to [timeoutSec] seconds. The caller owns the fd
     * and is responsible for calling [close].
     *
     * @throws IllegalStateException if `socket(2)` or `connect(2)` fails.
     */
    public fun rawConnect(port: Int, timeoutSec: Long = 5): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: ${errnoMessage(errno)}" }
        // SO_RCVTIMEO set via C wrapper to keep `struct timeval` field
        // widths inside C — Linux `time_t` and macOS `__darwin_time_t`
        // commonize to different Kotlin types under cinterop. Failure is
        // non-fatal: a stuck read would hang instead of timing out, but
        // the test can still complete on the happy path.
        keel_set_rcvtimeo(fd, timeoutSec.convert())
        while (true) {
            val rc = keel_connect_inet_loopback(fd, port.toUShort())
            if (rc == 0) break
            val err = errno
            if (err == EINTR) continue
            close(fd)
            error("connect() failed: rc=$rc ${errnoMessage(err)}")
        }
        return fd
    }

    /**
     * Writes every byte of [data] to [fd]. Loops on partial writes and
     * retries on `EINTR`.
     *
     * @throws IllegalStateException on unrecoverable errno or 0-byte
     *   return (TCP `write(2)` should never return 0 for non-empty data).
     */
    public fun rawWrite(fd: Int, data: ByteArray) {
        if (data.isEmpty()) return
        data.usePinned { pinned ->
            var total = 0
            while (total < data.size) {
                val n = write(fd, pinned.addressOf(total), (data.size - total).convert())
                when {
                    n > 0 -> total += n.toInt()
                    n == 0L -> error(
                        "write() returned 0 unexpectedly at offset $total/${data.size}",
                    )
                    else -> {
                        val err = errno
                        if (err == EINTR) continue
                        error(
                            "write() failed at offset $total/${data.size}: ${errnoMessage(err)}",
                        )
                    }
                }
            }
        }
    }

    /** Convenience overload: writes [data] encoded as UTF-8. */
    public fun rawWrite(fd: Int, data: String): Unit = rawWrite(fd, data.encodeToByteArray())

    /**
     * Reads exactly [size] bytes from [fd] into a new [ByteArray]. Loops
     * on partial reads and retries on `EINTR`.
     *
     * @throws IllegalStateException on EOF before [size] bytes received
     *   or on unrecoverable errno (EAGAIN after `SO_RCVTIMEO` / ECONNRESET
     *   / etc.). Includes the errno and short message for diagnosis.
     */
    public fun rawReadBytes(fd: Int, size: Int): ByteArray {
        val buf = ByteArray(size)
        var total = 0
        while (total < size) {
            val n = buf.usePinned { pinned ->
                read(fd, pinned.addressOf(total), (size - total).convert())
            }
            when {
                n > 0 -> total += n.toInt()
                n == 0L -> error("read() returned 0 (EOF) after $total/$size bytes")
                else -> {
                    val err = errno
                    if (err == EINTR) continue
                    error(
                        "read() failed after $total/$size bytes: ${errnoMessage(err)}",
                    )
                }
            }
        }
        return buf
    }

    /** Convenience overload: decodes the result as UTF-8. */
    public fun rawRead(fd: Int, size: Int): String = rawReadBytes(fd, size).decodeToString()

    /**
     * Reads up to [maxSize] bytes, returning whatever arrived before the
     * `SO_RCVTIMEO` window closes or EOF is hit. Unlike [rawRead] / [rawReadBytes],
     * a short payload terminated by `EAGAIN` / `EWOULDBLOCK` is a valid
     * outcome — suited for HTTP-response-style reads where the exact
     * response length isn't known up front.
     *
     * Still retries on `EINTR` and still throws on unrecoverable errors
     * (ECONNRESET etc.) so genuine faults surface loudly.
     */
    public fun rawReadUpTo(fd: Int, maxSize: Int): String {
        val buf = ByteArray(maxSize)
        var total = 0
        while (total < maxSize) {
            val n = buf.usePinned { pinned ->
                read(fd, pinned.addressOf(total), (maxSize - total).convert())
            }
            when {
                n > 0 -> total += n.toInt()
                n == 0L -> break // EOF — return what we have
                else -> {
                    val err = errno
                    if (err == EINTR) continue
                    if (err == EAGAIN || err == EWOULDBLOCK) break // SO_RCVTIMEO — return what we have
                    error("read() failed after $total/$maxSize bytes: ${errnoMessage(err)}")
                }
            }
        }
        return buf.decodeToString(0, total)
    }

    /**
     * Issues a single `read(2)` for up to [size] bytes and returns the
     * result: positive for bytes read, 0 for EOF, negative on error.
     * Retries transparently on `EINTR`.
     *
     * Intended for tests that want to observe EOF (e.g., `shutdownOutput`
     * verification) rather than insist on a full-payload read.
     */
    public fun rawReadOnce(fd: Int, size: Int): Int {
        val buf = ByteArray(size)
        while (true) {
            val n = buf.usePinned { pinned ->
                read(fd, pinned.addressOf(0), size.convert())
            }
            if (n >= 0) return n.toInt()
            val err = errno
            if (err == EINTR) continue
            return -1
        }
    }
}
