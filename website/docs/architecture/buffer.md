---
sidebar_position: 3
---

# IoBuf and BufferAllocator

## IoBuf

`IoBuf` is a reference-counted byte buffer with dual-pointer (readerIndex / writerIndex) semantics. Backed by native memory for zero-copy I/O.

| Target | Backing storage |
|---|---|
| JVM | `ByteBuffer.allocateDirect` |
| Native | `nativeHeap.allocArray<ByteVar>` |
| JS | `Int8Array` |

Always call `release()` (or `close()`) after use to avoid memory leaks. Use `retain()` when sharing a buffer across multiple owners.

## BufferAllocator

`BufferAllocator` is a pluggable interface for buffer allocation strategy. Each engine can use its optimal memory strategy:

| Allocator | Engine | Notes |
|---|---|---|
| `SlabAllocator` | epoll, kqueue, io_uring | Fixed-size slab per EventLoop, low fragmentation |
| `PooledDirectAllocator` | NIO | `ByteBuffer.allocateDirect` pool |
| `DefaultAllocator` | All | Simple allocation, no pooling |
| `HeapAllocator` | All | Heap-backed, for testing only |

Configure via `IoEngineConfig`:

```kotlin
val engine = KqueueEngine(
    config = IoEngineConfig(
        allocator = SlabAllocator(slabSize = 4096, slabCount = 64),
    ),
)
```

## Reference Counting

```kotlin
val buf = allocator.allocate(1024)
try {
    // write data...
    buf.retain()  // share with another owner
    // ...
} finally {
    buf.release() // decrement ref count
}
```

When the reference count reaches zero, the buffer is returned to the allocator (pooled) or freed (unpooled).

## TrackingAllocator

`TrackingAllocator` wraps any allocator and tracks all allocations for leak detection in tests:

```kotlin
val tracking = TrackingAllocator(SlabAllocator())
// ... run test ...
assertEquals(0, tracking.activeAllocations, "Buffer leak detected")
```
