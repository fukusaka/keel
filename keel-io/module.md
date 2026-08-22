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

**Reference counting**: buffers start with `refCount = 1`; `retain()`
(declared on `IoBuf`) increments, `release()` (the `Releasable` contract)
decrements. When the
count reaches zero the buffer's `IoBufOwner` strategy decides what happens
to the backing — free it, return it to a pool, or release a parent slice.
Engines may also implement `IoBuf` directly over their own kernel-managed
memory (e.g. `RingBufferIoBuf` in engine-io-uring).

**Ownership model** (see `website/docs/architecture/buffer.md` for details):

- **Writes transfer ownership.** `Channel.write(buf)` / `IoTransport.write(buf)` /
  `SuspendSink.write(buf)` / `ctx.propagateWrite(msg)` take over the caller's
  reference. After the call returns, do not touch the buffer (no read/write,
  no `release()`, no index inspection). The engine releases the buffer after
  flush completes.
- **Reads are non-transfer (the inverse).** `Channel.read(buf)` / `IoTransport.read(buf)`
  take a caller-allocated buffer as a slot to fill; ownership never leaves
  the caller, who releases when done.
- **`retain()` only when you deliberately need an extra reference** —
  fan-out to multiple sinks, holding across a suspension, or storing for
  later processing in a handler.

**Thread safety**: `retain()` / `release()` are atomic and safe from any
thread holding a reference; content access (read*/write*/index updates) is
single-threaded and requires a happens-before edge when handed between
threads.

**Raw memory access**: `unsafePointer` (Native) / `unsafeBuffer` (JVM) are
gated behind the `@UnsafeIoBufApi` opt-in — an unchecked escape hatch for
engine/codec hot paths, not for application code.

**Platform implementations**:

| Platform | Class | Backing memory |
|----------|-------|---------------|
| JVM | `DirectIoBuf` | `java.nio.ByteBuffer.allocateDirect` |
| Native | `NativeIoBuf` | `kotlinx.cinterop.nativeHeap.allocArray<ByteVar>` |
| JS | `TypedArrayIoBuf` | `org.khronos.webgl.Int8Array` |

`EmptyIoBuf` is a singleton zero-capacity `IoBuf` where all read/write
operations throw and `retain()`/`release()` are no-ops — a placeholder where
an `IoBuf` reference is structurally required but no data is present.

## Buffer Allocators

`BufferAllocator` is a pluggable interface: `allocate` / `wrapBytes` (zero-copy
`ByteArray` wrapping) / `slice` (retained sub-view) / `hintSizeClass`
(best-effort warm-cache hint) / `stats()` / `close()`.

| Allocator | Target | Strategy |
|-----------|--------|----------|
| `DefaultAllocator` | all | Allocates fresh on every call. Tests/fallback |
| `SlabAllocator` | Native | `PooledAllocator` with per-size-class spin-lock freelists |
| `PooledDirectAllocator` | JVM | `PooledAllocator` with per-size-class mutex freelists |

**Lifecycle-scoped children**: engines call `createChild()` to obtain an owned
allocator instance whose lifetime they control — one per EventLoop thread for
the thread-pinned engines, once per engine where there is no per-thread split.
Pool-based parents cascade-close their children on `close()`;
`createUntrackedChild()` produces a child the caller must close itself (e.g.
one allocator per accepted connection).

**Platform backing**: an engine hands read-buffer memory straight to the kernel
through an unchecked cast — to `NativePointerAccess` on the Native targets,
`NioByteBufferBacking` on the JVM, `TypedArrayIoBuf` on JS. A custom allocator
used with one must hand out buffers carrying that backing, and so must its
children. The epoll and kqueue engines ask once while being built
(`requireNativePointerAccess`) and refuse to start otherwise, naming the
allocator; on the others the same mistake surfaces later, in whatever form that
engine's read path gives it.

## Chunk-Based Pooling

`PooledAllocator` is the common pool skeleton behind both platform pools. It
installs a jemalloc/Netty-style size-class ladder: an `allocate` request is
rounded up to the smallest size class that can hold it and served from that
class's freelist, so any requested size becomes poolable. Pool misses are
carved from a shared, sharded chunk arena (internal; roughly one shard per
EventLoop) instead of hitting the platform heap per buffer; requests above
the ladder bypass pooling and are allocated at exact size. A total-bytes
budget caps worst-case cache residency.

Off-thread release safety is platform-specific. The Native `SlabAllocator`
classifies releases from a thread (or GCD queue) other than the owning one
via a `ConfinementToken` (`installConfinement`) and routes them back to the
owner through an MPSC return queue rather than corrupting the freelist —
keeping off-EventLoop consumers (e.g. `asSource` readers) safe. The JVM
`PooledDirectAllocator` instead relies on its mutex-locked freelists
(`MutexFreelist`) for off-thread safety; there `installConfinement` remains
the interface's no-op default.

## Observability

Three complementary channels, all wired through allocator constructor
parameters and propagated to `createChild()` children:

- `BufferAllocatorStatsCounter` — hot-path push hook with primitive/enum
  arguments only (`AllocPath`, `ReleaseOutcome`, `SizeTier`). `PoolMissProfile`
  implements it.
- `AllocatorStats` — pull-shape snapshot returned by `BufferAllocator.stats()`
  for telemetry adapters that poll on a collection cycle.
- `BufferAllocatorLifecycleListener` — identity-bearing allocate/release
  callbacks (receives the `IoBuf` itself) for leak detection and lifecycle
  audits; invoked uniformly, including for engine-direct `IoBuf` types.

Decorators: `TrackingAllocator` counts live allocations,
`LeakDetectingAllocator` reports buffers that are garbage-collected without
`release()`, and `ProfilingAllocator` records a requested-size histogram
(`AllocationProfile`).

## Chunked Payload Carriers

Variable-length payloads are carried as chains of pooled chunks instead of
one growing `ByteArray`:

- `IoBufChunks` — an owned, ordered, single-use list of pooled `IoBuf` chunks
  forming one logical payload; an encoder writes a length prefix from
  `totalSize` and gather-writes the chunks (one `writev`). `release()` frees
  the whole list on the abort path.
- `IoBufMutableChunks` — growable counterpart built by adding already-existing
  chunks (body aggregation, message reassembly); finalise exactly once with
  `toIoBufChunks()`, `toByteArray()`, or `release()`.
- `IoBufAccumulator` — append-only byte accumulator: a streaming codec writes
  into `writableChunk()` and `commit()`s filled chunks, avoiding both copies
  during accumulation and doubling-realloc churn.

## Suspend I/O Layer

`SuspendSource` and `SuspendSink` are the async I/O primitives that bridge
`IoBuf` to the codec layer:

| Interface | Method | Description |
|-----------|--------|-------------|
| `SuspendSource` | `suspend read(IoBuf): Int` | Fills a caller-owned `IoBuf`; returns bytes read or -1 on EOF |
| `SuspendSink` | `suspend write(IoBuf): Int` | Takes ownership of the buffer and queues it; returns bytes written |
| `OwnedSuspendSource` | `suspend readOwned(): IoBuf?` | Returns an engine-owned `IoBuf` (zero-copy push mode); `null` on EOF |

`BufferedSuspendSource` wraps either source kind and provides `readByte()` /
`readLine()` / `readByteArray(count)` / `readAtMostTo(dest, offset, length)`
— the primary API for codec parsers. Both modes
manage a chain of `IoBuf` segments (append at tail, release drained heads,
never compact):

- **Pull mode**: each refill allocates an 8 KiB `IoBuf` from the allocator and
  reads into it once; drained buffers are released back to the allocator.
- **Push mode**: each refill takes an engine-owned `IoBuf` from
  `readOwned()`. No allocation, no copy.

`BufferedSuspendSink` wraps a `SuspendSink` and provides `writeString()` /
`writeByte()` / `writeAscii()` with a single uniform deferred-flush strategy:
each filled buffer is handed off to the sink (which queues it) and the actual
OS write happens on `flush()` — batching a multi-buffer response into one
`writev` on every engine.

`KeelEofException` signals unexpected end-of-stream; codec-specific EOF
exceptions subclass it for layered catch handling.

## ScopeLocal

`ScopeLocal<T>` (package `io.github.fukusaka.keel.scope`) stores a value per
logical execution scope — the cross-platform generalization of a thread-local
for keel's mixed EventLoop models: a GCD dispatch queue on Apple
(`DispatchQueueLocal` with a per-pthread fallback), `@ThreadLocal` on Linux
Native, `java.lang.ThreadLocal` on JVM, and a singleton on JS. Only the read
side (`current`, `isScopedHere`) is common; obtain instances through the
`scopeLocal(fallback)` factory.

# Package io.github.fukusaka.keel.buf

`IoBuf`, `Releasable`, `IoBufOwner`, `EmptyIoBuf`, `BufferAllocator` and its
implementations (`DefaultAllocator`, `SlabAllocator`, `PooledDirectAllocator`,
`PooledAllocator`), observability hooks (`BufferAllocatorStatsCounter`,
`AllocatorStats`, `BufferAllocatorLifecycleListener`, `TrackingAllocator`,
`LeakDetectingAllocator`, `ProfilingAllocator`), chunked payload carriers
(`IoBufChunks`, `IoBufMutableChunks`, `IoBufAccumulator`), `IoBufAsciiText`
(zero-copy ASCII `CharSequence` view), `requireNativePointerAccess` (the
construction-time check a Native engine makes of the allocator it reads through),
and the `@UnsafeIoBufApi` opt-in.

# Package io.github.fukusaka.keel.io

`SuspendSource`, `SuspendSink`, `OwnedSuspendSource` (async I/O interfaces);
`BufferedSuspendSource`, `BufferedSuspendSink` (buffered wrappers with
line-oriented parsing and deferred flush); `KeelEofException`; number-parsing
helpers for decimal/hex fields.

# Package io.github.fukusaka.keel.scope

`ScopeLocal` and the `scopeLocal` factory — per-execution-scope storage bound
to each platform's native scope primitive.
