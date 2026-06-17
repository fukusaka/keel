package io.github.fukusaka.keel.buf

// JS uses the unpooled DefaultAllocator (V8 GC manages Int8Array), so the
// statsCounter parameter has no pool dispatch to instrument and is intentionally
// ignored. Accepting the parameter keeps the platform actuals' signature aligned
// with the commonMain expect so callers can pass a counter uniformly.
@Suppress("UNUSED_PARAMETER")
actual fun defaultAllocator(statsCounter: BufferAllocatorStatsCounter): BufferAllocator = DefaultAllocator
