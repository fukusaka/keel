package io.github.fukusaka.keel.buf

/**
 * A thin holder of a fixed-size raw memory region.
 *
 * A [Segment] pairs a [RawSegmentBacking] (the opaque platform memory)
 * with the [capacity] of that region. The platform [IoBuf]
 * implementations are *views* over a [Segment]: they read the platform
 * base out of [backing] once at construction, cache it, and keep their
 * own `readerIndex` / `writerIndex` / refcount.
 *
 * The [capacity] is its own field rather than a constant because a
 * "huge" segment (a request larger than the pooled size class) is
 * larger than the standard pooled [RawMemorySource] size.
 *
 * **Phase 1 note**: a [Segment] carries no reference count of its own —
 * the refcount stays on the [IoBuf] view (a 1:1 view/segment pairing).
 * This is internal scaffolding for later phases (composite buffers,
 * chunk allocator); it is behaviour-neutral.
 *
 * @property backing  The opaque platform memory region.
 * @property capacity Size of the region in bytes.
 */
internal class Segment(
    val backing: RawSegmentBacking,
    val capacity: Int,
)
