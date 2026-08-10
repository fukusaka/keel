package io.github.fukusaka.keel.engine.epoll

import io.github.fukusaka.keel.core.Host
import io.github.fukusaka.keel.core.InetSocketAddress
import io.github.fukusaka.keel.core.IoEngineConfig
import io.github.fukusaka.keel.core.IpAddress
import io.github.fukusaka.keel.native.posix.ConnectResult
import io.github.fukusaka.keel.native.posix.FakeNativeSocket
import io.github.fukusaka.keel.native.posix.FakeNativeSocketOps
import io.github.fukusaka.keel.testing.InjectedFault
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.dup
import platform.posix.socket
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * What a connect does when a step between the socket opening and the caller
 * receiving a channel fails.
 *
 * The mirror of [EpollChannelAcceptGuardSeamTest] on the other side of the
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
class EpollConnectGuardSeamTest {

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
            val engine = EpollEngine(
                config = IoEngineConfig(threads = 1),
                nativeSocket = FakeNativeSocket(),
                nativeSocketOps = fakeOps,
            )
            try {
                assertFailsWith<InjectedFault> {
                    engine.connect(InetSocketAddress(Host.Ip(IpAddress.parse("127.0.0.1")), 9))
                }

                assertFalse(
                    stillOpen(doomed),
                    "the connect never produced a channel, so nothing else will ever close this",
                )
            } finally {
                val leftOpen = dup(doomed)
                if (leftOpen >= 0) {
                    close(leftOpen)
                    close(doomed)
                }
                engine.close()
            }
        }
    }
}
