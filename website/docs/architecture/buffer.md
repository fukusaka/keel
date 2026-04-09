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

| Allocator | Target | Notes |
|---|---|---|
| `SlabAllocator` | Native | Fixed-size slab per EventLoop, low fragmentation |
| `PooledDirectAllocator` | JVM | `ByteBuffer.allocateDirect` pool |
| `DefaultAllocator` | All | Simple allocation, no pooling (tests / fallback) |

Configure via `IoEngineConfig`:

```kotlin
val engine = KqueueEngine(
    config = IoEngineConfig(
        allocator = SlabAllocator(bufferSize = 8192, maxPoolSize = 256),
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

## Leak Detection

keel provides two complementary tools for detecting buffer leaks:

| Tool | Purpose | Scope |
|---|---|---|
| `TrackingAllocator` | Count-based: "is there a leak?" | All platforms |
| `LeakDetectingAllocator` | Stack trace: "where was it allocated?" | Native (Cleaner), JVM (PhantomReference) |

Use the `withTracking()` and `withLeakDetection()` extension functions for fluent composition. Call `withTracking()` last to retain access to `assertNoLeaks()`:

```kotlin
val tracker = SlabAllocator()
    .withLeakDetection { msg -> fail(msg) }
    .withTracking()

// ... run test ...
tracker.assertNoLeaks()  // throws if any buffer was not released
```

`LeakDetectingAllocator` captures the allocation site stack trace. When a buffer is garbage-collected without being released, the `onLeak` callback fires with the stack trace. Detection relies on GC, so trigger it explicitly in tests:

- **Native**: `kotlin.native.runtime.GC.collect()`
- **JVM**: `System.gc()` (best-effort) + allocate to drain the queue
- **JS**: no-op (GC-managed, no manual release needed)
