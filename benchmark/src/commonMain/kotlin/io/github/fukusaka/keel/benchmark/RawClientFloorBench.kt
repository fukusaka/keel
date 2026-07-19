package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.core.StreamEngine
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.pipeline.SuspendMessageBridge
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
 * identical to the client's — `connect`, a `PipelinedChannel`, the channel's own
 * per-EventLoop allocator, a `SuspendMessageBridge` for the reply,
 * `requestWriteAndFlush` to send — and removes only the layers above, so the
 * difference between the two numbers is what those layers cost.
 *
 * **Match the client's path, not a plausible-looking one.** An earlier version
 * used the coroutine `Channel.read` / `write` / `flush` API instead. That is a
 * different route through the same channel — `flush` there awaits completion,
 * adding a per-request handshake the pipeline path does not have — and it
 * measured ~13.6 us on every engine, above the full JVM client's 10.9 us. A
 * floor above the thing it is a floor for is a broken instrument, and the
 * uniformity across engines was the tell.
 *
 * **A reply larger than one read buffer is framed, not assumed away.** A reply
 * arrives as however many inbound buffers the transport produces — around
 * thirteen for a 100 KB body against an 8 KiB read buffer. Consuming one buffer
 * per request would leave the rest queued in the bridge: throughput inflated by
 * that ratio, latency measured against a stale chunk of an earlier reply, and
 * the surplus pooled buffers never released. So the first buffer's
 * `Content-Length` sets how much body to wait for.
 *
 * Still not an HTTP client, and it must not be used as one: no chunked encoding,
 * no continuation responses, and a header that does not fit in the first inbound
 * buffer is reported as an error rather than guessed at.
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
    var channel: PipelinedChannel? = null
    var bridge: SuspendMessageBridge<IoBuf>? = null
    try {
        // Inside the try: a failed connect must still close the engine, or its
        // EventLoop threads keep a native process alive and the operator sees a
        // hang instead of the connection error.
        val opened = engine.connect(host, port)
        check(opened is PipelinedChannel) {
            "the floor needs a PipelinedChannel; got ${opened::class.simpleName}"
        }
        channel = opened
        val messages = SuspendMessageBridge(IoBuf::class, releaseUndelivered = { it.release() })
        bridge = messages
        withContext(opened.ioDispatcher) {
            opened.pipeline.addLast("floor-bridge", messages)
            opened.readEnabled = true
        }
        roundTrips(opened, messages, request, warmupSeconds)
        val result = roundTrips(opened, messages, request, durationSeconds)
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
        // A consumer-initiated teardown does not fire onInactive, so whatever the
        // bridge still holds is released here or not at all.
        bridge?.closeAndReleaseBuffered()
        channel?.close()
        engine.close()
    }
}

/** Sequential request/reply round trips for [seconds], on the channel's I/O dispatcher. */
private suspend fun roundTrips(
    channel: PipelinedChannel,
    bridge: SuspendMessageBridge<IoBuf>,
    request: ByteArray,
    seconds: Int,
): FloorResult = withContext(channel.ioDispatcher) {
    // The channel's own allocator, not the engine's root: engines never allocate
    // from the root — each derives a child per EventLoop and allocates from that.
    // The root has a different size-class ladder, so measuring against it would
    // not be the path the client takes, and buffers left in it are never drained
    // by the engine's close.
    val allocator = channel.allocator
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
        if (!receiveWholeReply(bridge)) {
            errors++
            break // the peer went away, or the reply was unframeable; either way a
            // partial run reported as a full one would lie
        }
        latency.record(mark.elapsedNow().inWholeNanoseconds.coerceAtLeast(1))
        completed++
    }
    FloorResult(completed, errors, started.elapsedNow().inWholeNanoseconds, latency)
}

/**
 * Receives one complete reply, releasing every buffer it consumes. Returns false
 * when the connection ends first, or when the first buffer does not carry a
 * whole header — this deliberately reports that rather than guessing at framing.
 */
private suspend fun receiveWholeReply(bridge: SuspendMessageBridge<IoBuf>): Boolean {
    val first = bridge.receiveCatching().getOrNull() ?: return false
    val firstLength = first.readableBytes
    val header = first.peekAscii()
    first.release()

    val headerEnd = header.indexOf(HEADER_TERMINATOR)
    if (headerEnd < 0) return false // header spans buffers: out of scope, not guessed
    val contentLength = contentLengthOf(header.substring(0, headerEnd)) ?: return true
    var remaining = contentLength - (firstLength - (headerEnd + HEADER_TERMINATOR.length))
    while (remaining > 0) {
        val next = bridge.receiveCatching().getOrNull() ?: return false
        remaining -= next.readableBytes
        next.release()
    }
    return true
}

/** The buffer's readable bytes as ASCII, for header inspection only. */
private fun IoBuf.peekAscii(): String {
    val n = minOf(readableBytes, MAX_HEADER_PEEK)
    val bytes = ByteArray(n)
    readByteArray(bytes, 0, n)
    return bytes.decodeToString()
}

/** `Content-Length` from a header block, or null when the reply declares none. */
private fun contentLengthOf(header: String): Long? {
    val idx = header.indexOf(CONTENT_LENGTH, ignoreCase = true)
    if (idx < 0) return null
    val valueStart = idx + CONTENT_LENGTH.length
    val lineEnd = header.indexOf('\r', valueStart).let { if (it < 0) header.length else it }
    return header.substring(valueStart, lineEnd).trim().toLongOrNull()
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
private const val MAX_HEADER_PEEK = 2048
private const val HEADER_TERMINATOR = "\r\n\r\n"
private const val CONTENT_LENGTH = "content-length:"
private const val FLOOR_P50 = 50.0
private const val FLOOR_P99 = 99.0
private const val FLOOR_P999 = 99.9
private const val FLOOR_P100 = 100.0
