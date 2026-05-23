package io.github.fukusaka.keel.buf.poc

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.buf.poc.cand1.Cand1IoBuf
import io.github.fukusaka.keel.buf.poc.cand2.Cand2IoBuf

/**
 * Allocates a fresh segment via [allocator] and appends it to this
 * [Cand1IoBuf]'s chain. Public so the cross-module PoC bench (under
 * engine-nio / engine-kqueue test sources) can grow a Cand1 buffer
 * without reaching into keel-io's `internal extractSegment` shim.
 *
 * **PoC-scoped**: removed alongside `buf.poc.*` once the multi-seg
 * IoBuf candidate decision lands.
 */
public fun Cand1IoBuf.appendNewSegment(allocator: BufferAllocator, segmentCapacity: Int) {
    appendSegment(extractSegment(allocator.allocate(segmentCapacity)))
}

/** Counterpart to [appendNewSegment] for [Cand2IoBuf]. */
public fun Cand2IoBuf.appendNewSegment(allocator: BufferAllocator, segmentCapacity: Int) {
    appendSegment(extractSegment(allocator.allocate(segmentCapacity)))
}
