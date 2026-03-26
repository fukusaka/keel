package io.github.fukusaka.keel.io

// JS has no pool allocator (GC-managed). Pool-specific tests are
// skipped via isPoolAllocator() returning false.
actual fun createPoolAllocator(bufferSize: Int, maxPoolSize: Int): BufferAllocator =
    HeapAllocator

actual fun isPoolAllocator(): Boolean = false
