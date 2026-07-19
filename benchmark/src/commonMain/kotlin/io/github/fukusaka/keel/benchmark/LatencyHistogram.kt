package io.github.fukusaka.keel.benchmark

import kotlin.math.ceil
import kotlin.math.nextDown

/**
 * A minimal HdrHistogram for latency percentiles, in common code so the Native
 * and JS client drivers can report the same p50 / p99 / p99.9 / max as the JVM
 * and the reference clients.
 *
 * Every other client driver in this harness measures percentiles with an
 * HdrHistogram port at three significant figures — the JVM with
 * `org.HdrHistogram`, C with the vendored HdrHistogram_c, and Rust / Go / Swift
 * with their ecosystems' ports. Kotlin/Native has none, which left the keel
 * client measurable only on the JVM. This is that missing port: the recording
 * and percentile algorithms transcribed from the vendored C implementation, so
 * a Kotlin/Native driver lands in the same buckets as every peer it is compared
 * against.
 *
 * **Why the numbers are comparable.** Bucket boundaries are fixed by
 * [significantFigures] alone: the sub-bucket count is the smallest power of two
 * covering `2 * 10^figures`, and a value's bucket is derived from its magnitude.
 * [highestTrackableValue] only sizes the backing array. So this port and the C
 * / JVM ports agree on every value they both track, and
 * `LatencyHistogramTest` pins that by comparing against `org.HdrHistogram`
 * itself over random input rather than trusting the transcription.
 *
 * **Deviation from the C source, deliberate.** The C computes the sub-bucket
 * magnitude as `ceil(log(x) / log(2))` in floating point. This uses an integer
 * ceiling-log2, which is exact and cannot land on the wrong side of a power of
 * two through rounding. For the values in play the two agree; the integer form
 * simply removes the question.
 *
 * Not thread-safe, and deliberately not a general-purpose histogram: it records
 * and reports percentiles, and does nothing else the harness does not need.
 */
internal class LatencyHistogram(
    private val lowestDiscernibleValue: Long = DEFAULT_LOWEST,
    private val highestTrackableValue: Long = DEFAULT_HIGHEST,
    significantFigures: Int = DEFAULT_SIGNIFICANT_FIGURES,
) {

    private val unitMagnitude: Int
    private val subBucketHalfCountMagnitude: Int
    private val subBucketCount: Int
    private val subBucketHalfCount: Int
    private val subBucketMask: Long
    private val counts: LongArray

    /** Number of recorded values. */
    var totalCount: Long = 0
        private set

    /** The largest value as recorded, before bucket rounding. */
    private var rawMaxValue: Long = 0

    /**
     * Largest recorded value, or 0 when nothing was recorded — reported as the
     * top of its bucket, which is what `hdr_max` and `org.HdrHistogram` both
     * return. Reporting the raw value instead would put this port's max
     * systematically below every other driver's by up to one bucket width,
     * reading as a better tail than the peers actually measured.
     */
    val maxValue: Long
        get() = if (rawMaxValue == 0L) 0L else highestEquivalentValue(rawMaxValue)

    init {
        require(lowestDiscernibleValue >= 1) { "lowestDiscernibleValue must be >= 1" }
        require(significantFigures in 1..MAX_SIGNIFICANT_FIGURES) { "significantFigures must be 1..5" }
        require(lowestDiscernibleValue * 2 <= highestTrackableValue) {
            "highestTrackableValue must be at least twice lowestDiscernibleValue"
        }
        val largestValueWithSingleUnitResolution = 2L * pow10(significantFigures)
        val subBucketCountMagnitude = ceilLog2(largestValueWithSingleUnitResolution)
        subBucketHalfCountMagnitude = maxOf(subBucketCountMagnitude, 1) - 1
        unitMagnitude = floorLog2(lowestDiscernibleValue)
        subBucketCount = 1 shl (subBucketHalfCountMagnitude + 1)
        subBucketHalfCount = subBucketCount / 2
        subBucketMask = (subBucketCount.toLong() - 1) shl unitMagnitude
        val bucketCount = bucketsNeededToCoverValue(highestTrackableValue)
        counts = LongArray((bucketCount + 1) * subBucketHalfCount)
    }

    /**
     * Records [value], returning false (and recording nothing) when it falls
     * outside the tracked range — the same contract as `hdr_record_value`.
     */
    fun record(value: Long): Boolean {
        if (value < 0 || value > highestTrackableValue) return false
        val index = countsIndexFor(value)
        if (index < 0 || index >= counts.size) return false
        counts[index]++
        totalCount++
        if (value > rawMaxValue) rawMaxValue = value
        return true
    }

    /**
     * Merges [other] into this histogram. Both must share the same
     * configuration — checked on the configuration itself, not on the array
     * size, which is not a proxy for it: at three significant figures
     * `(lowest=1, highest=60e9)` and `(lowest=2, highest=120e9)` both produce
     * 27,648 counts under different value-to-index mappings, so a size-only
     * check would merge them into silently wrong percentiles.
     */
    fun add(other: LatencyHistogram) {
        require(
            other.lowestDiscernibleValue == lowestDiscernibleValue &&
                other.highestTrackableValue == highestTrackableValue &&
                other.unitMagnitude == unitMagnitude &&
                other.counts.size == counts.size,
        ) { "cannot merge histograms with different configurations" }
        for (i in counts.indices) counts[i] += other.counts[i]
        totalCount += other.totalCount
        if (other.rawMaxValue > rawMaxValue) rawMaxValue = other.rawMaxValue
    }

    /**
     * The value at [percentile] (0..100), reported as the highest value in the
     * bucket the percentile falls in — HdrHistogram's convention, so a reported
     * figure is an upper bound on the true one within the histogram's precision.
     *
     * **This follows `org.HdrHistogram`, not the vendored C.** The two disagree:
     * the C converts a percentile to a sample position with
     * `(p / 100 * count) + 0.5` truncated, while the reference implementation
     * takes `ceil(nextDown(p) * count / 100)`. Because neither `p` nor `p / 100`
     * is exact in binary, the two land on different sample positions at some
     * percentile-and-count combinations — p99.99 over 5000 samples picks the
     * 4999th under the C rule and the 5000th under this one, which can name a
     * different value when the tail is sparse. The reference is the original
     * implementation and the one the JVM driver in this harness uses, so
     * matching it keeps keel's Native and JVM numbers exactly comparable.
     */
    fun valueAtPercentile(percentile: Double): Long {
        if (totalCount == 0L) return 0
        val requested = minOf(maxOf(percentile.nextDown(), 0.0), PERCENT_FULL)
        val countAtPercentile = maxOf(ceil(requested * totalCount / PERCENT_FULL).toLong(), 1L)
        var countToIndex = 0L
        for (index in counts.indices) {
            countToIndex += counts[index]
            if (countToIndex >= countAtPercentile) {
                val value = valueAtIndex(index)
                return if (percentile == 0.0) lowestEquivalentValue(value) else highestEquivalentValue(value)
            }
        }
        return 0
    }

    private fun bucketsNeededToCoverValue(value: Long): Int {
        var smallestUntrackableValue = subBucketCount.toLong() shl unitMagnitude
        var bucketsNeeded = 1
        while (smallestUntrackableValue <= value) {
            if (smallestUntrackableValue > Long.MAX_VALUE / 2) return bucketsNeeded + 1
            smallestUntrackableValue = smallestUntrackableValue shl 1
            bucketsNeeded++
        }
        return bucketsNeeded
    }

    private fun bucketIndexOf(value: Long): Int {
        // The smallest power of two containing the value, minus the fixed
        // sub-bucket span — i.e. how many times the sub-bucket range doubles
        // before it covers this magnitude.
        val pow2Ceiling = Long.SIZE_BITS - (value or subBucketMask).countLeadingZeroBits()
        return pow2Ceiling - unitMagnitude - (subBucketHalfCountMagnitude + 1)
    }

    private fun subBucketIndexOf(value: Long, bucketIndex: Int): Int =
        (value ushr (bucketIndex + unitMagnitude)).toInt()

    private fun countsIndex(bucketIndex: Int, subBucketIndex: Int): Int {
        val bucketBaseIndex = (bucketIndex + 1) shl subBucketHalfCountMagnitude
        val offsetInBucket = subBucketIndex - subBucketHalfCount
        return bucketBaseIndex + offsetInBucket
    }

    private fun countsIndexFor(value: Long): Int {
        val bucketIndex = bucketIndexOf(value)
        return countsIndex(bucketIndex, subBucketIndexOf(value, bucketIndex))
    }

    private fun valueFromIndex(bucketIndex: Int, subBucketIndex: Int): Long =
        subBucketIndex.toLong() shl (bucketIndex + unitMagnitude)

    private fun valueAtIndex(index: Int): Long {
        var bucketIndex = (index shr subBucketHalfCountMagnitude) - 1
        var subBucketIndex = (index and (subBucketHalfCount - 1)) + subBucketHalfCount
        if (bucketIndex < 0) {
            subBucketIndex -= subBucketHalfCount
            bucketIndex = 0
        }
        return valueFromIndex(bucketIndex, subBucketIndex)
    }

    private fun lowestEquivalentValue(value: Long): Long {
        val bucketIndex = bucketIndexOf(value)
        return valueFromIndex(bucketIndex, subBucketIndexOf(value, bucketIndex))
    }

    private fun sizeOfEquivalentValueRange(value: Long): Long {
        val bucketIndex = bucketIndexOf(value)
        val subBucketIndex = subBucketIndexOf(value, bucketIndex)
        val adjustedBucket = if (subBucketIndex >= subBucketCount) bucketIndex + 1 else bucketIndex
        return 1L shl (unitMagnitude + adjustedBucket)
    }

    private fun highestEquivalentValue(value: Long): Long =
        lowestEquivalentValue(value) + sizeOfEquivalentValueRange(value) - 1

    companion object {
        /** 1 ns — the finest latency the harness distinguishes. */
        const val DEFAULT_LOWEST: Long = 1

        /** 60 s in nanoseconds, matching the C reference driver's bound. */
        const val DEFAULT_HIGHEST: Long = 60_000_000_000L

        /** Three significant figures, the precision every driver in this harness uses. */
        const val DEFAULT_SIGNIFICANT_FIGURES: Int = 3

        private const val MAX_SIGNIFICANT_FIGURES = 5
        private const val PERCENT_FULL = 100.0

        private fun pow10(exponent: Int): Long {
            var result = 1L
            repeat(exponent) { result *= 10 }
            return result
        }

        /** Smallest `n` with `2^n >= value`; exact, unlike a floating-point log. */
        private fun ceilLog2(value: Long): Int {
            var n = 0
            var covered = 1L
            while (covered < value) {
                covered = covered shl 1
                n++
            }
            return n
        }

        /** Largest `n` with `2^n <= value`. */
        private fun floorLog2(value: Long): Int = (Long.SIZE_BITS - 1) - value.countLeadingZeroBits()
    }
}
