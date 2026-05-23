package io.github.fukusaka.keel.buf

/**
 * No-op [RawSegmentBacking] used by chain-primitive tests that exercise
 * indexing / iteration / refcount lifecycle but never read or write
 * actual bytes. Sharing one instance across test segments is safe because
 * [free] is a no-op.
 *
 * Implements [SegmentBacking] via the [RawSegmentBacking] supertype, so
 * a `Segment(FakeSegmentBacking, capacity)` produces a chain entry that
 * carries the marker but performs no platform allocation.
 */
internal object FakeSegmentBacking : RawSegmentBacking {
    override fun free() {
        // No-op: nothing to release.
    }
}

/** Constructs a fresh [Segment] of [capacity] bytes backed by [FakeSegmentBacking]. */
internal fun makeTestSegment(capacity: Int): Segment =
    Segment(FakeSegmentBacking, capacity)
