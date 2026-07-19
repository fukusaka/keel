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
 * Ties round away from zero rather than to even, which is what `String.format`
 * does and `kotlin.math.round` does not — the latter renders 0.0005 as `0.000`
 * where the JVM has always written `0.001`.
 *
 * The match is not exact in general: scaling happens in binary first, so a value
 * whose exact decimal sits just below a tie can be lifted onto it (0.0045
 * formats as `0.004` and renders here as `0.005`). It is exact for what this
 * harness formats. Latency comes from a histogram whose reported values are
 * always one below a power of two, which never lands on a decimal tie, and the
 * throughput column uses zero decimals, where the multiply is exact. Widening
 * the inputs — a different histogram precision, or decimals on the rps column —
 * would need this revisited rather than assumed.
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

/**
 * Splits a transport-floor target into host and port, rejecting anything but
 * `http://`. Without the check an `https://` target reaches the port parse as
 * `"//host:port"` and fails with a bare `NumberFormatException` that says
 * nothing about what was wrong.
 */
internal fun floorHostPort(target: String): Pair<String, Int> {
    require(target.startsWith(HTTP_SCHEME)) { "the floor supports http:// targets only; got '$target'" }
    val authority = target.removePrefix(HTTP_SCHEME)
    val port = authority.substringAfter(':', "").toIntOrNull()
        ?: error("floor target needs an explicit port: '$target'")
    return authority.substringBefore(':') to port
}

private const val HTTP_SCHEME = "http://"
