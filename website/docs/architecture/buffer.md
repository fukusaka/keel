---
sidebar_position: 3
---

# IoBuf and BufferAllocator

## IoBuf

`IoBuf` is keel's byte buffer type. It uses reference counting to manage lifetime:

| Target | Backing storage |
|---|---|
| JVM | `ByteBuffer.allocateDirect` (off-heap) |
| Native | `nativeHeap.allocArray<ByteVar>` (off-heap) |
| JS | `Int8Array` (GC-managed by V8) |

The buffer is divided into three regions:

```
+-------------------+------------------+------------------+
| discardable bytes | readable bytes   | writable bytes   |
+-------------------+------------------+------------------+
|                   |                  |                  |
0      <=      readerIndex   <=   writerIndex    <=    capacity
```

`readableBytes` (`writerIndex - readerIndex`) is the number of bytes available to read. `writableBytes` (`capacity - writerIndex`) is the remaining write capacity. Call `compact()` to discard already-read bytes and reclaim writable space — it moves the readable region to the start of the buffer.

Always call `release()` when you are done with a buffer — this returns it to the allocator pool or frees the native memory. `close()` forces immediate deallocation regardless of the reference count; prefer `release()` for normal lifecycle management.

**Thread safety**: `IoBuf` is designed for single-threaded use within an EventLoop thread. Concurrent access from multiple threads requires external synchronisation.

## Reference Counting

When one owner needs to hand a buffer to another owner (e.g., passing to a pipeline handler), call `retain()` before the handoff. Each `retain()` must be balanced by a corresponding `release()`:

```kotlin
val buf = allocator.allocate(1024)
// ... fill buf with data ...

buf.retain()          // hand off to another owner; refcount becomes 2
anotherOwner.use(buf) // anotherOwner will call release() when done

buf.release()         // original owner releases; refcount back to 0 → buffer freed/pooled
```

When the reference count reaches zero, the buffer is returned to the allocator (if pooled) or freed (if not pooled).

## BufferAllocator

`BufferAllocator` is a pluggable interface for controlling how `IoBuf` instances are allocated. Each engine uses the allocator best suited for its platform:

| Allocator | Target | Notes |
|---|---|---|
| `SlabAllocator` | Native | Fixed-size slab per EventLoop, low fragmentation |
| `PooledDirectAllocator` | JVM | `ByteBuffer.allocateDirect` pool |
| `DefaultAllocator` | All | Simple allocation, no pooling — for tests, fallback, and JS (default for JS production; V8 GC manages `Int8Array` so pooling is unnecessary) |

The `io_uring` engine uses a kernel-managed `ProvidedBufferRing` for inbound reads and does not use `BufferAllocator` for those paths.

Configure via `IoEngineConfig`:

```kotlin
val engine = KqueueEngine(
    config = IoEngineConfig(
        allocator = SlabAllocator(bufferSize = 8192, maxPoolSize = 256),
    ),
)
```

## Large Payload Optimization

When using Ktor via `keel-ktor-engine`, response bytes flow from Ktor's response API through `BufferedSuspendSink` before reaching keel's transport layer. For large payloads (8 KiB or above), keel skips the intermediate scratch buffer and passes the byte array directly to the transport as an `IoBuf` view:

```kotlin
// In a Ktor route handler:
call.respondBytes(smallPayload)  // < 8 KiB: copied through scratch buffer
call.respondBytes(largePayload)  // ≥ 8 KiB: wrapped as IoBuf view, skips scratch-buffer copy on JVM
```

This optimization applies on JVM via `wrapBytesAsIoBuf`, which wraps the caller's `ByteArray` in a heap-backed `ByteBuffer` view without a Kotlin-side copy. NIO still copies the heap buffer to direct memory once per syscall, but this is far cheaper than chunking the body through the 8 KiB scratch buffer. On Native and JS, this optimization is not available — all writes go through the chunked copy path.

## Leak Detection

keel provides two complementary tools for detecting buffer leaks:

| Tool | Purpose | Scope |
|---|---|---|
| `TrackingAllocator` | Count-based: confirms whether a leak exists | All platforms |
| `LeakDetectingAllocator` | GC-based: reports the allocation stack trace of each leaked buffer | Native (Cleaner), JVM (PhantomReference) |

Use the `withTracking()` and `withLeakDetection()` extension functions for fluent composition. Call `withTracking()` last so `assertNoLeaks()` is accessible:

```kotlin
val tracker = DefaultAllocator
    .withLeakDetection { msg -> fail(msg) }
    .withTracking()

// ... run test ...
tracker.assertNoLeaks()  // throws if any buffer was not released
```

`LeakDetectingAllocator` captures the allocation site stack trace. When a buffer is garbage-collected without being released, the `onLeak` callback fires with the stack trace. Detection relies on GC, so trigger it explicitly in tests:

- **Native**: `kotlin.native.runtime.GC.collect()`
- **JVM**: `System.gc()` (best-effort hint), then call `tracker.allocate(1).release()` to drain the `PhantomReference` queue
- **JS**: no-op (GC-managed, no manual release needed)
