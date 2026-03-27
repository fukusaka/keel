package io.github.fukusaka.keel.io

actual fun defaultAllocator(): BufferAllocator = SlabAllocator()
