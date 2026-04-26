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
 * The same six-bucket split is documented in `.claude/rules/testing.md`
 * § "テストカテゴリ"; this file (and the categorised test files below it)
 * is the concrete realisation for the Node.js engine.
 */

private var udsSeq = 0

internal fun uniqueUdsPath(): String {
    val seq = udsSeq++
    // Node's process.pid is available in Node.js runtime.
    val pid: Int = js("process.pid") as Int
    return "/tmp/keel-nodejs-uds-$pid-$seq.sock"
}
