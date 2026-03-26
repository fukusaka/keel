package io.github.fukusaka.keel.io

// JS has no pool allocator (GC-managed). Use HeapAllocator as fallback.
// Pool-specific tests (reuse, maxPoolSize) will pass vacuously since
// HeapAllocator always creates fresh buffers.
actual fun createPoolAllocator(bufferSize: Int, maxPoolSize: Int): BufferAllocator =
    HeapAllocator
