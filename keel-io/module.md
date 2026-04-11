# Module keel-io

Buffer primitives and async I/O abstractions used by all keel engines and codecs.

## IoBuf

`IoBuf` is the fundamental data carrier in keel's I/O pipeline.
It is a fixed-capacity, reference-counted byte buffer:

```
+-------------------+------------------+------------------+
| discardable bytes | readable bytes   | writable bytes   |
+-------------------+------------------+------------------+
0      <=      readerIndex   <=   writerIndex    <=    capacity
```

Data flows from the kernel through `IoBuf` to the codec layer and back:

```
kernel recv → IoBuf → BufferedSuspendSource → codec (readLine/readByte)
codec (writeAscii/writeByte) → BufferedSuspendSink → IoBuf → kernel send
```

**Reference counting**: `retain()` increments, `release()` decrements. When the
count reaches zero, the backing memory is freed. Engines call `retain()` when
buffering a write; the flush completion callback calls `release()`.

**Platform implementations**:

| Platform | Class | Backing memory |
|----------|-------|---------------|
| JVM | `DirectIoBuf` | `java.nio.ByteBuffer.allocateDirect` |
| Native | `NativeIoBuf` | `kotlinx.cinterop.nativeHeap.allocArray<ByteVar>` |
| JS | `TypedArrayIoBuf` | `org.khronos.webgl.Int8Array` |

## Buffer Allocators

`BufferAllocator` is a pluggable interface. Each engine uses the allocator
best suited for its platform:

| Allocator | Target | Strategy |
|-----------|--------|----------|
| `DefaultAllocator` | all | Allocates fresh on every call. Tests/fallback |
| `SlabAllocator` | Native | Per-EventLoop nativeHeap pool. No locking |
| `PooledDirectAllocator` | JVM | Per-EventLoop `DirectByteBuffer` pool |

Engines call `createForEventLoop()` once per EventLoop thread to get a
thread-local allocator instance, eliminating lock contention.

`TrackingAllocator` wraps any allocator to count live allocations.
`LeakDetectingAllocator` wraps any allocator and uses GC-based detection to
report `IoBuf` instances that are never released.

## BufSlice

`BufSlice` is a zero-copy read-only view into a region of an `IoBuf`.
Holds a reference to the parent `IoBuf` and is released when closed.
Used by codec parsers to expose parsed fields without copying bytes.

## Suspend I/O Layer

`SuspendSource` and `SuspendSink` are the async I/O primitives that bridge
`IoBuf` to the codec layer:

| Interface | Method | Description |
|-----------|--------|-------------|
| `SuspendSource` | `suspend read(IoBuf): Int` | Fills `IoBuf`; returns bytes read or -1 on EOF |
| `SuspendSink` | `suspend write(IoBuf): Int` | Drains `IoBuf`; returns bytes written |
| `OwnedSuspendSource` | `suspend readOwned(): IoBuf?` | Returns an engine-owned `IoBuf` (zero-copy push mode) |

`BufferedSuspendSource` wraps a `SuspendSource` or `OwnedSuspendSource` and
provides `readLine()` / `readByte()` / `readFully()` — the primary API for
codec parsers (HTTP, WebSocket):

- **Pull mode**: uses a single 8 KiB `IoBuf` as internal buffer. Suspends when exhausted.
- **Push mode**: manages a chain of engine-owned `IoBuf`s. No internal buffer allocation.

`BufferedSuspendSink` wraps a `SuspendSink` and provides `writeString()` /
`writeByte()` / `writeAscii()`. Flush strategy:

- **`deferFlush = true`** (kqueue/epoll/NIO): accumulates writes; a single `flush()` call triggers `writev`.
- **`deferFlush = false`** (Netty/NWConnection/Node.js): each full buffer immediately triggers an OS write.

# Package io.github.fukusaka.keel.buf

`IoBuf` (reference-counted buffer), `BufferAllocator`, `BufSlice`,
`TrackingAllocator`, `LeakDetectingAllocator`, and platform-specific
implementations (`NativeIoBuf`, `DirectIoBuf`, `TypedArrayIoBuf`, `SlabAllocator`,
`PooledDirectAllocator`).

# Package io.github.fukusaka.keel.io

`SuspendSource`, `SuspendSink`, `OwnedSuspendSource` (async I/O interfaces);
`BufferedSuspendSource`, `BufferedSuspendSink` (buffered wrappers with
line-oriented parsing and deferred flush).
