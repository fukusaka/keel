package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.AbstractIoBuf
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.NativeIoBuf
import kotlin.time.DurationUnit
import kotlin.time.TimeSource
import kotlin.time.measureTime

/**
 * Release-build native micro-bench for [NativeIoBuf] per-byte dispatch
 * paths after the [AbstractIoBuf] extraction in PR #617. Invoked via
 * `--bench=iobuf-per-byte`.
 *
 * Compares three receiver-type call sites for [IoBuf.writeByte] /
 * [IoBuf.readByte] / [IoBuf.getByte]:
 *
 * - **concrete** — receiver typed as [NativeIoBuf] (static dispatch),
 * - **abstract** — receiver typed as [AbstractIoBuf] (virtual dispatch
 *   via the common base lifted in PR #617),
 * - **interface** — receiver typed as [IoBuf] (interface dispatch).
 *
 * Goal: quantify the K/N Apple Silicon dispatch overhead when callers
 * widen the receiver beyond the concrete type. Release-build numbers
 * (a sibling `nativeTest`-form bench exists for debug-build sanity).
 *
 * Methodology mirrors [runSegmentAccessBench]: 2 s warmup, 5 s trial
 * × 3 runs per variant, median ns/op reported. One op = a full
 * [BYTES_PER_OP]-byte loop. A [blackhole] accumulator defeats DCE.
 */
fun runIoBufPerByteDispatchBench() {
    println("IoBuf per-byte dispatch micro-bench (Kotlin/Native, release)")
    println("============================================================")
    println("op|variant|ns/op|ns/byte|notes")

    @Suppress("IoBufLeak")
    val writeBuf = NativeIoBuf(CAPACITY)
    try {
        val writeAbstract: AbstractIoBuf = writeBuf
        val writeInterface: IoBuf = writeBuf

        benchVariant("writeByte", "concrete  receiver=NativeIoBuf  ") {
            writeBuf.clear()
            for (i in 0 until BYTES_PER_OP) writeBuf.writeByte(i.toByte())
            blackhole += writeBuf.writerIndex.toLong()
        }
        benchVariant("writeByte", "abstract  receiver=AbstractIoBuf") {
            writeAbstract.clear()
            for (i in 0 until BYTES_PER_OP) writeAbstract.writeByte(i.toByte())
            blackhole += writeAbstract.writerIndex.toLong()
        }
        benchVariant("writeByte", "interface receiver=IoBuf        ") {
            writeInterface.clear()
            for (i in 0 until BYTES_PER_OP) writeInterface.writeByte(i.toByte())
            blackhole += writeInterface.writerIndex.toLong()
        }
    } finally {
        writeBuf.release()
    }

    @Suppress("IoBufLeak")
    val readBuf = NativeIoBuf(CAPACITY).also { b ->
        for (i in 0 until CAPACITY) b.writeByte(i.toByte())
    }
    try {
        val readAbstract: AbstractIoBuf = readBuf
        val readInterface: IoBuf = readBuf

        benchVariant("readByte", "concrete  receiver=NativeIoBuf  ") {
            readBuf.readerIndex = 0
            var sum = 0L
            for (i in 0 until BYTES_PER_OP) sum += readBuf.readByte().toLong()
            blackhole += sum
        }
        benchVariant("readByte", "abstract  receiver=AbstractIoBuf") {
            readAbstract.readerIndex = 0
            var sum = 0L
            for (i in 0 until BYTES_PER_OP) sum += readAbstract.readByte().toLong()
            blackhole += sum
        }
        benchVariant("readByte", "interface receiver=IoBuf        ") {
            readInterface.readerIndex = 0
            var sum = 0L
            for (i in 0 until BYTES_PER_OP) sum += readInterface.readByte().toLong()
            blackhole += sum
        }

        benchVariant("getByte", "concrete  receiver=NativeIoBuf  ") {
            var sum = 0L
            for (i in 0 until BYTES_PER_OP) sum += readBuf.getByte(i).toLong()
            blackhole += sum
        }
        benchVariant("getByte", "abstract  receiver=AbstractIoBuf") {
            var sum = 0L
            for (i in 0 until BYTES_PER_OP) sum += readAbstract.getByte(i).toLong()
            blackhole += sum
        }
        benchVariant("getByte", "interface receiver=IoBuf        ") {
            var sum = 0L
            for (i in 0 until BYTES_PER_OP) sum += readInterface.getByte(i).toLong()
            blackhole += sum
        }
    } finally {
        readBuf.release()
    }

    println()
    println("blackhole=$blackhole") // DCE guard
}

private const val CAPACITY = 256
private const val BYTES_PER_OP = 128
private const val WARMUP_MS = 2_000L
private const val TRIAL_MS = 5_000L

@kotlin.concurrent.Volatile
private var blackhole: Long = 0

private inline fun benchVariant(op: String, label: String, body: () -> Unit) {
    warmupBench(body)
    val nsPerOp = trialMedianBench(body)
    val nsPerByte = nsPerOp / BYTES_PER_OP
    println("$op|$label|${format1(nsPerOp)}|${format2(nsPerByte)}|1 op = ${BYTES_PER_OP}B loop")
}

private inline fun warmupBench(body: () -> Unit) {
    val deadline = TimeSource.Monotonic.markNow()
    var iters = 0L
    while (deadline.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < WARMUP_MS) {
        repeat(1_000) {
            body()
            iters++
        }
    }
    blackhole += iters
}

private inline fun trialMedianBench(body: () -> Unit): Double {
    val nsPerRun = DoubleArray(3)
    for (i in 0 until 3) {
        var iters = 0L
        val elapsed = measureTime {
            val deadline = TimeSource.Monotonic.markNow()
            while (deadline.elapsedNow().toDouble(DurationUnit.MILLISECONDS) < TRIAL_MS) {
                repeat(1_000) {
                    body()
                    iters++
                }
            }
        }
        nsPerRun[i] = elapsed.toDouble(DurationUnit.NANOSECONDS) / iters.toDouble()
    }
    nsPerRun.sort()
    return nsPerRun[1]
}

private fun format1(v: Double): String =
    (kotlin.math.round(v * 10.0) / 10.0).toString()

private fun format2(v: Double): String =
    (kotlin.math.round(v * 100.0) / 100.0).toString()
