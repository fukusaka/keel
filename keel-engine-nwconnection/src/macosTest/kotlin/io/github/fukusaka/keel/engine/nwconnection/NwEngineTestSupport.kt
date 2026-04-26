package io.github.fukusaka.keel.engine.nwconnection

import io.github.fukusaka.keel.native.posix.PosixRawClient
import platform.posix.getpid

/**
 * Shared helpers + constants for the categorised `NwEngine*Test` files.
 *
 * Test category split:
 *
 * | file | scope |
 * |---|---|
 * | [NwEngineLifecycleTest] | engine create/close, server bind/close, error paths, double close, UDS variants |
 * | [NwEngineReadWriteTest] | echo, multi-write, half-close, `asSuspendSource` / `asSuspendSink` |
 * | [NwEngineConnectTest]   | client `connect()` flows |
 * | [NwEngineConcurrencyTest] | concurrent reads, FIFO accept queue, multi-client accept, cancellation |
 * | [NwEngineResourceTest]  | `TrackingAllocator` leak detection + GC heap stability |
 *
 * Mirrors the same category split applied to the other engine test
 * suites (kqueue / epoll / io_uring / nio / netty / nwconnection /
 * nodejs). This file is the concrete realisation for the NWConnection engine.
 */

// Per-operation hang-detection timeout for tests that exercise
// accept / read / job completion. Short enough to surface a real
// hang (normal latency on loopback is <50ms locally, <500ms on CI)
// but long enough not to flake on CI runners under load.
internal const val IO_OP_TIMEOUT_MS = 5_000L
internal const val IO_OP_SHORT_TIMEOUT_MS = 3_000L

// Per-operation timeout used specifically by the GC heap echo-cycle
// test. Separate constant so the heap-echo loop can be tuned
// independently from the other NWConnection tests if its
// retention-sensitive workload needs a different bound.
internal const val GC_ECHO_OP_TIMEOUT_MS = 3_000L

private var udsPathSeq = 0

internal fun uniqueUdsPath(): String {
    val pid = getpid()
    val seq = udsPathSeq++
    return "/tmp/keel-nw-uds-$pid-$seq.sock"
}

internal fun connectRawClient(port: Int): Int = PosixRawClient.rawConnect(port)

internal fun rawWrite(fd: Int, data: String): Unit = PosixRawClient.rawWrite(fd, data)

internal fun rawRead(fd: Int, size: Int): String = PosixRawClient.rawRead(fd, size)
