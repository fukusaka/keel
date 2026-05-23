package io.github.fukusaka.keel.buf

/**
 * Callback shape for the callback-style multi-segment iteration API on
 * [IoBuf] / [SegmentChain].
 *
 * Declared as a `fun interface` so HotSpot can SAM-eliminate the lambda
 * and Kotlin/Native's whole-program LTO can inline the apply across
 * module boundaries — both observed in the multi-seg IoBuf PoC bench
 * (PR #602, closed). Engines use this shape on the platforms where it
 * outperforms the [SegmentRangeList] iteration (Native: ~34 % cross-
 * module on release LTO).
 *
 * The callback receives a window
 * `[offset, offset + length)` into [memory]'s local coordinate space.
 * Treat the arguments as transient — they are valid only for the
 * duration of this invocation.
 */
public fun interface SegmentRangeAction {

    /**
     * Invoked once per readable window in the iterated chain, in order
     * from head to tail.
     */
    public fun apply(memory: SegmentBacking, offset: Int, length: Int)
}
