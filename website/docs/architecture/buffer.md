---
sidebar_position: 3
---

# NativeBuf and BufferAllocator

## NativeBuf

`NativeBuf` is a fixed-capacity byte buffer backed by native memory.

```kotlin
// commonMain
expect class NativeBuf(capacity: Int) {
    val capacity: Int
    fun writeByte(value: Byte)
    fun readByte(): Byte
    fun close()
}
```

| Target | Backing storage |
|---|---|
| JVM | `ByteBuffer.allocateDirect` |
| Native | `nativeHeap.allocArray<ByteVar>` |

Always call `close()` after use to avoid memory leaks.

## BufferAllocator (Phase 5)

Phase 5 will introduce a pluggable `BufferAllocator` interface so each engine
can use its optimal memory strategy.

| Allocator | Engine | Notes |
|---|---|---|
| `SlabAllocator` | epoll, kqueue | Fixed-size slab, low fragmentation |
| `PooledDirectAllocator` | NIO | `ByteBuffer.allocateDirect` pool |
| `NettyBufAllocator` | Netty | Delegates to Netty's `PooledByteBufAllocator` |
| `JsNativeBufAllocator` | Node.js | V8 GC managed |
| `HeapAllocator` | All | Heap-backed, for testing only |

`IoUringFixedAllocator` (Phase 6) pins buffer addresses for
`io_uring` fixed-buffer registration (`IORING_REGISTER_BUFFERS`).
