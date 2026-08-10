package io.github.fukusaka.keel.engine.kqueue

import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.native.posix.ConnectResult
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import io.github.fukusaka.keel.testing.InjectedFault
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.AF_UNIX
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.dup
import platform.posix.socket
import platform.posix.socketpair
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What a connect does when a step between the socket opening and the caller
 * receiving a channel fails.
 *
 * The mirror of [KqueueChannelAcceptGuardSeamTest] on the other side of the
 * transport: from `socket(2)` until the channel is returned, the descriptor is
 * known only to the frame holding it — the transport is not in the loop's
 * registry until the channel attaches, so no stop notification reaches it
 * either. A throw in that stretch left it open for the process's life, once
 * per connect attempt, on a socket the peer believes is connected.
 *
 * The reachable one is the address query: `getpeername` is a `check` over the
 * syscall, and a peer that resets between the connection completing and the
 * query answers `ENOTCONN`. The rest are guards against a step gaining a throw.
 */
@OptIn(ExperimentalForeignApi::class)
class KqueueConnectGuardSeamTest {

    /**
     * Whether [fd] still names an open descriptor, without keeping the
     * duplicate it asks with.
     */
    private fun stillOpen(fd: Int): Boolean {
        val probe = dup(fd)
        if (probe < 0) return false
        close(probe)
        return true
    }

    @Test
    fun `a connect whose peer address cannot be read releases the descriptor`() = runBlocking {
        withTimeout(15.seconds) {
            // A real descriptor, handed to the engine by the fake opener, so
            // the assertion is about a socket rather than a number: the fake
            // fabricates fds otherwise and `dup` would answer for whatever
            // else holds that number.
            val doomed = socket(AF_INET, SOCK_STREAM, 0)
            assertTrue(doomed >= 0, "could not open a socket for the engine to connect with")
            val fakeOps = FakeNativeSocketOps().apply {
                nextCreatedFd = doomed
                defaultConnect = ConnectResult.Connected
                getRemoteAddressThrowsOnce = InjectedFault("getpeername() failed: ENOTCONN")
            }
            val engine = KqueueEngine(
                config = IoEngineConfig(threads = 1),
                nativeSocket = FakeNativeSocket(),
                nativeSocketOps = fakeOps,
            )
            try {
                assertFailsWith<InjectedFault> {
                    engine.connect(InetSocketAddress(Host.Ip(IpAddress.parse("127.0.0.1")), 9))
                }

                // Closed before the probe, not after. Until the loops are
                // joined they can open a descriptor, and the number the guard
                // just released is the lowest free one -- `dup` would then
                // answer for a stranger's socket and this would fail for the
                // wrong reason.
                //
                // Deliberately not reclaimed afterwards either: on a passing
                // run the guard has closed it, and closing it again would be
                // closing whatever took it. A failing run leaks it, which is
                // the failure being reported.
                engine.close()
                assertFalse(
                    stillOpen(doomed),
                    "the connect never produced a channel, so nothing else will ever close this",
                )
            } finally {
                engine.close()
            }
        }
    }

    @Test
    fun `a connect whose socket error cannot be read releases the descriptor`() = runBlocking {
        withTimeout(15.seconds) {
            // The in-progress path: the descriptor comes back from the await
            // owned by this frame again, and `getsockopt(SO_ERROR)` is the
            // first thing that touches it. A throw there is the same loss as
            // the address query, one await later.
            // One end of a socketpair, not a bare socket: the engine waits for
            // write-readiness on this descriptor before reading SO_ERROR, and
            // an unconnected socket never becomes writable.
            val pair = IntArray(2)
            val paired = pair.usePinned { socketpair(AF_UNIX, SOCK_STREAM, 0, it.addressOf(0)) == 0 }
            assertTrue(paired, "could not open a socket pair for the engine to connect with")
            val doomed = pair[0]
            val peer = pair[1]
            val fakeOps = FakeNativeSocketOps().apply {
                nextCreatedFd = doomed
                defaultConnect = ConnectResult.InProgress
                getSocketErrorThrowsOnce = InjectedFault("getsockopt(SO_ERROR) failed: EBADF")
            }
            val engine = KqueueEngine(
                config = IoEngineConfig(threads = 1),
                nativeSocket = FakeNativeSocket(),
                nativeSocketOps = fakeOps,
            )
            try {
                assertFailsWith<InjectedFault> {
                    engine.connect(InetSocketAddress(Host.Ip(IpAddress.parse("127.0.0.1")), 9))
                }

                // Closed before the probe, not after. Until the loops are
                // joined they can open a descriptor, and the number the guard
                // just released is the lowest free one -- `dup` would then
                // answer for a stranger's socket and this would fail for the
                // wrong reason.
                //
                // Deliberately not reclaimed afterwards either: on a passing
                // run the guard has closed it, and closing it again would be
                // closing whatever took it. A failing run leaks it, which is
                // the failure being reported.
                engine.close()
                assertFalse(
                    stillOpen(doomed),
                    "the connect never produced a channel, so nothing else will ever close this",
                )
            } finally {
                close(peer)
                engine.close()
            }
        }
    }
}
