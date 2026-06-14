package io.github.fukusaka.keel.buf

// JS uses the unpooled DefaultAllocator (V8 GC manages Int8Array), so the
// missProfile parameter has no pool to instrument and is intentionally ignored.
// Accepting the parameter keeps the platform actuals' signature aligned with
// the commonMain expect so callers can pass a profile uniformly.
@Suppress("UNUSED_PARAMETER")
actual fun defaultAllocator(missProfile: PoolMissProfile?): BufferAllocator = DefaultAllocator
