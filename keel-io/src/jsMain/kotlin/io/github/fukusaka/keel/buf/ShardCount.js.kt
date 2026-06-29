package io.github.fukusaka.keel.buf

// JS runs on a single-threaded event loop and the pooling allocator is not used
// there (DefaultAllocator has no chunk arena), so the shard count is irrelevant.
internal actual fun availableProcessors(): Int = 1
