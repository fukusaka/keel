package io.github.fukusaka.keel.benchmark

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

/**
 * Pieces of the client harness that must be identical on every platform.
 *
 * The JVM and Native client benchmarks are separate implementations — they use
 * different HTTP clients, different clocks and different concurrency primitives
 * — but a comparison between them is only meaningful if they agree on what a
 * target URL is and on how a result line is written. Those two things live here
 * so a change to either cannot land on one platform and not the other.
 */

/**
 * Resolves the fixture URLs a run drives, from `--client-target` plus the
 * endpoint path.
 *
 * The fixture must be a separate process: sharing a process with the client
 * under test contaminates both sides' numbers, so there is deliberately no
 * in-process option.
 */
internal fun clientTargets(cc: ClientConfig): List<String> {
    val raw = cc.targetUrl
        ?: error(
            "client bench requires --client-target=<url>[,<url>...] pointing at SEPARATE fixture " +
                "process(es) (e.g. rust-bench on loopback). bench-client.sh starts / stops the fixture. " +
                "In-process fixtures are unsupported: sharing the client process contaminates the numbers.",
        )
    val targets = raw.split(',')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { it.trimEnd('/') + cc.endpoint }
    require(targets.isNotEmpty()) { "no target URL parsed from --client-target='$raw'" }
    return targets
}

/**
 * The machine-parseable result line `bench-client.sh` reads:
 * `<name><endpoint>|<req/s>|<p50>|<p99>|<p99.9>|<max>|<bytes/op>|<errors>`,
 * latencies in milliseconds. [bytesPerOp] is `n/a` where the platform has no
 * allocation counter, matching the native reference drivers.
 */
internal fun formatClientResultLine(
    name: String,
    reqPerSec: Double,
    p50: Double,
    p99: Double,
    p999: Double,
    max: Double,
    bytesPerOp: String,
    errors: Long,
): String = buildString {
    append(name).append('|')
    append(reqPerSec.roundTo(0)).append('|')
    append(p50.roundTo(LATENCY_DECIMALS)).append('|')
    append(p99.roundTo(LATENCY_DECIMALS)).append('|')
    append(p999.roundTo(LATENCY_DECIMALS)).append('|')
    append(max.roundTo(LATENCY_DECIMALS)).append('|')
    append(bytesPerOp).append('|')
    append(errors)
}

/**
 * Fixed-point rendering of [decimals] places, without a platform string
 * formatter (Kotlin/Native has no `String.format`). Keeps the trailing zeros the
 * JVM's `%.3f` produces so both platforms emit the same column widths.
 *
 * Ties round away from zero, matching `String.format`'s HALF_UP — not
 * `kotlin.math.round`, which breaks ties towards even and would render 0.0005
 * as `0.000` where the JVM has always written `0.001`.
 */
internal fun Double.roundTo(decimals: Int): String {
    if (isNaN() || isInfinite()) return "0"
    val factor = 10.0.pow(decimals)
    val rounded = floor(abs(this) * factor + HALF).toLong()
    val sign = if (this < 0) "-" else ""
    if (decimals == 0) return "$sign$rounded"
    val scale = factor.toLong()
    val whole = rounded / scale
    val frac = rounded % scale
    return "$sign$whole.${frac.toString().padStart(decimals, '0')}"
}

private const val LATENCY_DECIMALS = 3
private const val HALF = 0.5
