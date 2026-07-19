package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.pipeline.SuspendMessageBridge
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * The floor beneath the client benchmark: one connection driven through the
 * pipeline exactly as the real client drives it, but with no HTTP codec — raw
 * request bytes out, raw reply bytes back.
 *
 * It exists to localise a per-request cost. The full client measures transport
 * plus codec plus pool plus the client's own wrappers, so a gap between two
 * engines there could come from any of them. This keeps the transport path
 * identical to the client's — `connect`, a `PipelinedChannel`, a
 * `SuspendMessageBridge` for the reply, `requestWriteAndFlush` to send — and
 * removes only the layers above, so the difference between the two numbers is
 * what those layers cost.
 *
 * **Match the client's path, not a plausible-looking one.** An earlier version
 * used the coroutine `Channel.read` / `write` / `flush` API instead. That is a
 * different route through the same channel — `flush` there awaits completion,
 * adding a per-request handshake the pipeline path does not have — and it
 * measured ~13.6 µs on every engine, above the full JVM client's 10.9 µs. A
 * floor above the thing it is a floor for is a broken instrument, and the
 * uniformity across engines was the tell.
 *
 * **Deliberately not a general benchmark.** It assumes a small reply arrives as
 * one inbound buffer, true for a loopback fixture returning a few hundred bytes.
 * It is not an HTTP client and must not be used as one.
 */
internal suspend fun runRawClientFloor(
    engine: StreamEngine,
    host: String,
    port: Int,
    path: String,
    durationSeconds: Int,
    warmupSeconds: Int,
    label: String,
) {
    val request = "GET $path HTTP/1.1\r\nHost: $host:$port\r\nConnection: keep-alive\r\n\r\n".encodeToByteArray()
    run {
        val channel = engine.connect(host, port)
        try {
            check(channel is PipelinedChannel) {
                "the floor needs a PipelinedChannel; got ${channel::class.simpleName}"
            }
            val bridge = SuspendMessageBridge(IoBuf::class, releaseUndelivered = { it.release() })
            withContext(channel.ioDispatcher) {
                channel.pipeline.addLast("floor-bridge", bridge)
                channel.readEnabled = true
            }
            roundTrips(channel, bridge, engine, request, warmupSeconds)
            val result = roundTrips(channel, bridge, engine, request, durationSeconds)
            println(
                formatClientResultLine(
                    name = "$label$path",
                    reqPerSec = result.reqPerSec,
                    p50 = result.latency.valueAtPercentile(FLOOR_P50) / NANOS_PER_MILLI,
                    p99 = result.latency.valueAtPercentile(FLOOR_P99) / NANOS_PER_MILLI,
                    p999 = result.latency.valueAtPercentile(FLOOR_P999) / NANOS_PER_MILLI,
                    max = result.latency.valueAtPercentile(FLOOR_P100) / NANOS_PER_MILLI,
                    bytesPerOp = "n/a",
                    errors = result.errors,
                ),
            )
        } finally {
            channel.close()
            engine.close()
        }
    }
}

/** Sequential request/reply round trips for [seconds], on the channel's I/O dispatcher. */
private suspend fun roundTrips(
    channel: PipelinedChannel,
    bridge: SuspendMessageBridge<IoBuf>,
    engine: StreamEngine,
    request: ByteArray,
    seconds: Int,
): FloorResult = withContext(channel.ioDispatcher) {
    val allocator = engine.config.allocator
    val clock = TimeSource.Monotonic
    val started = clock.markNow()
    val deadline = seconds.toLong() * NANOS_PER_SECOND_FLOOR
    val latency = LatencyHistogram()
    var completed = 0L
    var errors = 0L
    while (started.elapsedNow().inWholeNanoseconds < deadline) {
        val mark = clock.markNow()
        // The pipeline takes ownership of the buffer, as the codec's output does.
        val out = allocator.allocate(request.size)
        out.writeByteArray(request, 0, request.size)
        channel.pipeline.requestWriteAndFlush(out)
        val received = bridge.receiveCatching()
        val reply = received.getOrNull()
        if (reply == null) {
            errors++
            break // the peer went away; a partial run reported as a full one would lie
        }
        reply.release()
        latency.record(mark.elapsedNow().inWholeNanoseconds.coerceAtLeast(1))
        completed++
    }
    FloorResult(completed, errors, started.elapsedNow().inWholeNanoseconds, latency)
}

private class FloorResult(
    val completed: Long,
    val errors: Long,
    val elapsedNanos: Long,
    val latency: LatencyHistogram,
) {
    val reqPerSec: Double
        get() = if (elapsedNanos <= 0) 0.0 else completed.toDouble() * NANOS_PER_SECOND_FLOOR / elapsedNanos
}

private const val NANOS_PER_SECOND_FLOOR = 1_000_000_000L
private const val NANOS_PER_MILLI = 1_000_000.0
private const val FLOOR_P50 = 50.0
private const val FLOOR_P99 = 99.0
private const val FLOOR_P999 = 99.9
private const val FLOOR_P100 = 100.0
