package io.github.fukusaka.keel.buf

// JS has no pool allocator (GC-managed). Pool-specific tests are
// skipped via isPoolAllocator() returning false.
actual fun createPoolAllocator(): BufferAllocator = DefaultAllocator

// JS has no pool allocator, so the profile is never recorded into. Returning
// DefaultAllocator keeps the symbol present for cross-platform test compile;
// guarded test bodies check isPoolAllocator() and return early on JS.
@Suppress("UNUSED_PARAMETER")
actual fun createPoolAllocatorWithProfile(profile: PoolMissProfile): BufferAllocator = DefaultAllocator

actual fun isPoolAllocator(): Boolean = false
