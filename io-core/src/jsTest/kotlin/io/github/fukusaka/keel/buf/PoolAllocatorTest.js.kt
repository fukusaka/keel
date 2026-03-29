package io.github.fukusaka.keel.buf

// JS has no pool allocator (GC-managed). Pool-specific tests are
// skipped via isPoolAllocator() returning false.
actual fun createPoolAllocator(bufferSize: Int, maxPoolSize: Int): BufferAllocator =
    DefaultAllocator

actual fun isPoolAllocator(): Boolean = false
