package io.github.fukusaka.keel.engine.nodejs

/**
 * Shared helpers + constants for the categorised `NodeEngine*Test` files.
 *
 * Test category split:
 *
 * | file | scope |
 * |---|---|
 * | [NodeEngineLifecycleTest] | engine create/close, server bind/close, error paths, double close, UDS variants |
 * | [NodeEngineReadWriteTest] | echo, multi-write, half-close, `asSuspendSource` / `asSuspendSink` |
 * | [NodeEngineConnectTest]   | client `connect()` flows |
 * | [NodeEngineConcurrencyTest] | concurrent accept FIFO queue |
 *
 * Mirrors the same category split applied to the other engine test
 * suites (kqueue / epoll / io_uring / nio / netty / nwconnection).
 */

private var udsSeq = 0

internal fun uniqueUdsPath(): String {
    val seq = udsSeq++
    // Node's process.pid is available in Node.js runtime.
    val pid: Int = js("process.pid") as Int
    return "/tmp/keel-nodejs-uds-$pid-$seq.sock"
}
