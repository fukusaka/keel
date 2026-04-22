---
sidebar_position: 3
---

# IoBuf and BufferAllocator

## Summary

- `IoBuf` is keel's byte buffer type. Reading and writing go through `readByte()` and `writeByte()`.
- Call `release()` when use ends. GC is not relied upon; explicit release is required.
- The ownership model is **two-layered**:
  - **Inside the pipeline (between handlers)**: ownership transfer — once you call `ctx.propagateRead(msg)`, do not touch the buffer.
  - **At the Transport / Channel boundary**: retain-on-input — `channel.write(buf)` causes the transport to retain the buffer and consume its `readerIndex`. The caller must also call `release()`.

`IoBuf` follows the same design family as Netty's `ByteBuf`. The pipeline layer uses the same transfer convention as Netty, but at the `channel.write` level keel requires more explicit release management. Compared with NIO's `ByteBuffer`, the additions are reference counting and explicit `release()`.

## Overview

`IoBuf` is a reference-counted byte buffer. The backing storage differs per platform; the API is common.

| Target | Backing storage |
|---|---|
| JVM | `ByteBuffer.allocateDirect` (off-heap) |
| Native | `nativeHeap.allocArray<ByteVar>` (native memory) |
| JS | `Int8Array` (V8 heap) |

### Why reference counting is required

On JVM and Native the backing storage lives outside the managed heap. The GC only reclaims in-heap objects, so off-heap memory would remain allocated unless explicitly freed. `release()` is required to avoid the leak.

On JS, `Int8Array` is GC-managed, but the same call convention applies for cross-platform API consistency. `release()` on JS is effectively a no-op.

### Thread safety contract

`IoBuf` stores its `refCount` as a **non-atomic bare `Int`** — not even `@Volatile`. This contrasts with Netty `ByteBuf`, which uses `AtomicIntegerFieldUpdater`.

This is a deliberate design choice, not an oversight. It rests on the following contract:

- **All `refCount` updates for a given buffer happen from the single thread that currently owns the buffer.**

Ownership singleness is guaranteed by the transfer semantics defined in the next section. When ownership is transferred across threads, the transfer mechanism itself (EventLoop `dispatch`, Netty `EventLoop.execute`, NWConnection `dispatch_queue_async`, etc.) **establishes a happens-before relation**, so the receiving thread observes the prior `refCount` / index values correctly. `@Volatile` or atomic CAS is therefore unnecessary.

Supporting machinery on the keel side:

- All engines align `ioDispatcher` with their worker EventLoop. Transport-layer operations always run on the EL.
- Push-based engines (NWConnection / Netty) align to their EL thread via `NwConnectionQueueDispatcher` / `NettyEventLoopDispatcher`. Callbacks delivered through `SuspendBridgeHandler` resume on the same EL.
- `Channel.write(buf)` queues the buffer internally; dequeue + flush + release all run on the same EL.

### How the contract gets broken

The following code violates the contract:

```kotlin
override fun channelRead(ctx: HandlerContext, msg: Any) {
    val buf = msg as IoBuf
    coroutineScope.launch(Dispatchers.Default) {
        // Runs on the Default thread pool, not the EL
        processAsync(buf)     // refCount is non-atomic; race possible
        buf.release()          // release also from the Default thread
    }
}
```

- Switching dispatchers with `withContext(Dispatchers.IO)` and touching the buffer there.
- Holding a buffer across a coroutine suspension while explicitly changing the resume dispatcher.
- Passing a buffer to another thread explicitly (e.g., into an `ArrayDeque` drained by a separate executor).

In these patterns, `refCount` updates may be lost or invisible from another thread. Symptoms manifest as native segfaults, silent leaks or double-frees on JVM, and silent corruption on JS.

### Contrast with Netty's atomic approach

Netty `ByteBuf` uses atomic CAS to allow cross-thread sharing safely. keel prefers to keep the hot-path cost low and chose non-atomic.

| | keel `IoBuf` | Netty `ByteBuf` |
|---|---|---|
| `refCount` | Non-atomic `Int` | `AtomicIntegerFieldUpdater` (CAS) |
| Cross-thread sharing | Contract violation (assumes EL alignment) | Allowed (atomic guards it) |
| Hot-path cost | Increment / decrement + boundary check | CAS loop (x86 `LOCK` prefix, ARM LL/SC) |
| Measured overhead | ~0 | A few ns per operation times the hit count |
| On user misuse | Undefined behaviour | Safe (no leak, no corruption) |

Both are defensible designs; the divergence reflects different use contexts (Netty as a general-purpose Java networking library, keel as a thin I/O layer for KMP). keel's choice relies on the following three premises:

1. EL alignment is guaranteed on the engine side.
2. When user handlers need blocking I/O, they offload via `withContext(Dispatchers.IO)` explicitly, and **do not carry the buffer across the boundary** (release or transfer before blocking).
3. No use case currently requires cross-thread buffer sharing (Ktor / pipeline / codec all stay on the EL).

If that premise breaks (for example, a user wants to aggregate many buffers in a parallel pipeline), options include adding an atomic `IoBuf` variant or routing through `keel-engine-netty` and working with Netty `ByteBuf` directly.

### Three-region layout

```
+-------------------+------------------+------------------+
| discardable bytes | readable bytes   | writable bytes   |
+-------------------+------------------+------------------+
|                   |                  |                  |
0      <=      readerIndex   <=   writerIndex    <=    capacity
```

- `readerIndex` — read cursor, advanced by `readByte()`.
- `writerIndex` — write cursor, advanced by `writeByte()`.
- `compact()` discards the discardable region and reclaims writable space.

`readableBytes = writerIndex - readerIndex`, `writableBytes = capacity - writerIndex`. The index model is equivalent to Netty `ByteBuf`.

## Ownership model

Nearly every reference-count bug stems from a misunderstanding of this model.

### Two-layer ownership model

keel's buffer ownership uses **two different models depending on the layer**. Mixing them up is how leaks and double-releases happen; get this part right first.

| Layer | Model | APIs |
|---|---|---|
| **Inside the pipeline (between handlers)** | Ownership transfer (do not touch after passing) | `onRead` / `onWrite` / `ctx.propagateRead` / `ctx.propagateWrite` / `transport.onRead` callback |
| **At the Transport / Channel boundary** | Retain-on-input (caller and transport both hold a ref) | `Channel.write(buf)` / `IoTransport.write(buf)` / `SuspendSink.write(buf)` |
| **Read-side APIs** | Non-transfer (the caller allocates a buffer for the engine to fill) | `Channel.read(buf)` / `IoTransport.read(buf)` |

#### Layer 1: ownership transfer inside the pipeline

When a buffer is passed between handlers, ownership is transferred. The sender must not touch the buffer afterwards. The final handler releases it (or propagates it onward via `propagateRead`).

```kotlin
class HttpDecoder : InboundHandler<IoBuf> {
    override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
        try {
            // The handler owns `msg`. Produce a slice and pass it downstream.
            val slice = ctx.allocator.slice(msg, msg.readerIndex, bytesToEmit)
            ctx.propagateRead(HttpBody(slice))   // slice ownership is transferred downstream
        } finally {
            msg.release()     // handler releases the original msg (responsibility of the owner)
        }
    }
}
```

This matches the Netty `ByteBuf` pipeline convention.

#### Layer 2: retain-on-input at the Transport / Channel boundary

When user code calls `channel.write(buf)`, **the transport retains internally** and **consumes the caller's `readerIndex`**. The caller is still responsible for releasing its own reference.

```kotlin
val buf = allocator.allocate(128)                // refCount = 1
buf.writeAscii("hello", 0, 5)                   // writerIndex = 5
channel.write(buf)                                // transport retains (refCount = 2);
                                                  // buf.readerIndex advances to 5 (consumed)
channel.flush()
buf.release()                                     // caller releases its own reference (refCount = 1)
// Transport releases its retained reference after flush (refCount = 0, memory freed)
```

**If the caller forgets to call `release()`, the buffer leaks** — the transport's retain alone keeps refCount at 1; the memory is never freed.

Why this design: it makes it natural to write a header and a body into the same buffer and call `channel.write` repeatedly (common in codec layers) — no `buf.retain()` ceremony like Netty's `channel.writeAndFlush(buf.retain())`. The trade-offs are the leak risk from forgotten `release()` calls and the asymmetry with the pipeline layer.

#### Layer 3: non-transfer for read APIs

`channel.read(buf)` / `transport.read(buf)` take a caller-allocated buffer and let the engine fill it. Ownership stays with the caller, who releases when done.

```kotlin
val buf = allocator.allocate(8192)
val n = channel.read(buf)       // n bytes written into buf; ownership unchanged
processData(buf)
buf.release()
```

### API ownership classification

**A. Pipeline layer: ownership transfer** (do not touch after passing)

| API | Release timing on the receiving side |
|---|---|
| `transport.onRead(buf)` callback | Pipeline HEAD → final handler in the chain |
| `onRead(ctx, msg)` / `onReadTyped(ctx, msg)` | The handler itself (typically via `try/finally`) |
| `ctx.propagateRead(msg)` / `ctx.propagateWrite(msg)` | Next downstream / upstream handler |
| `ctx.propagateUserEvent(evt)` | Downstream handler (when `evt` is an `IoBuf`) |

**B. Transport / Channel boundary: retain-on-input** (caller also releases)

| API | Transport side | Caller side |
|---|---|---|
| `Channel.write(buf)` | Calls `buf.retain()` internally; releases after flush | `readerIndex` is consumed. **Must call `release()` on its own reference.** |
| `IoTransport.write(buf)` | Same | Same |
| `SuspendSink.write(buf: IoBuf)` | Same (impl-dependent) | Same |

**C. Non-transfer APIs** (caller retains ownership)

| API | Caller's responsibility |
|---|---|
| `Channel.read(buf)` / `IoTransport.read(buf)` | Release after the buffer contents have been consumed |
| `buf.readByte()` / `writeByte()` / `getByte(i)` / `readByteArray(...)` / `writeByteArray(...)` | Ownership unchanged (only indices advance) |
| `buf.copyTo(dest, length)` | Both source and dest remain caller-owned |
| `buf.compact()` / `clear()` | Ownership unchanged |

**D. APIs returning a new buffer** (caller takes ownership)

| API | Returned refCount | Release responsibility |
|---|---|---|
| `allocator.allocate(size)` | 1 | The final consumer |
| `allocator.wrapBytes(bytes, offset, length)` | 1 (non-null only on allocators that support wrapping) | The final consumer (input `bytes` stays caller-owned) |
| `allocator.slice(src, offset, length)` | 1 (slice has its own count) | The slice owner (`src` is managed internally by the allocator) |
| `buf.retain()` | Existing refCount + 1, same instance returned | Whoever created the additional reference |

`BufferedSuspendSink.write(bytes: ByteArray, offset, length)` takes a `ByteArray` and is out of scope for this classification. Internally the sink creates an `IoBuf` via either `wrapBytes` or a scratch copy; the sink releases that `IoBuf` itself.

### When to call `retain()`

`retain()` is called only when the caller needs to hold an additional reference. Three scenarios apply. This is a concern for the pipeline layer (ownership transfer); at the Transport / Channel boundary the caller implicitly retains its ref under retain-on-input, so `retain()` is usually unnecessary.

**(1) A pipeline handler stores `msg` in a field for use in a later event**

```kotlin
class DelayedEcho : InboundHandler<IoBuf> {
    private var cached: IoBuf? = null

    override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
        cached = msg.retain()        // handler's own reference (+1)
        ctx.propagateRead(msg)        // original reference transferred downstream
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        cached?.release()
        cached = null
    }
}
```

**(2) Fan-out `msg` to multiple downstream handlers inside the pipeline**

```kotlin
override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
    ctx.propagateRead(msg.retain())   // +1 reference for the primary downstream
    ctx.propagateRead(msg)             // original reference for the secondary downstream
    // Each downstream handler releases exactly once.
}
```

Call `retain()` **before** each ownership transfer. The last transfer receives the original reference, so `retain()` is not needed for it. For N propagations, `retain()` is called `N - 1` times.

**(3) Crossing an async boundary**

When `msg` must outlive the caller's scope (for example, across a coroutine suspension), `retain()` before the original owner releases to keep the buffer alive for your continuation.

**Fan-out writes at the Transport / Channel boundary are a separate problem.** `channel.write(buf)` consumes `readerIndex`, so simply handing the same buffer to two channels produces zero readable bytes on the second call. Options:

```kotlin
// Option 1: save and restore readerIndex
val saved = buf.readerIndex
channel1.write(buf)       // retains + consumes readerIndex
buf.readerIndex = saved    // reset
channel2.write(buf)       // retains + reads the same range again
buf.release()              // caller releases its reference

// Option 2: use slices to obtain independent views (allocator.slice retains src internally)
val view1 = allocator.slice(buf, buf.readerIndex, bytes)
val view2 = allocator.slice(buf, buf.readerIndex, bytes)
channel1.write(view1); channel2.write(view2)
view1.release(); view2.release()   // slice owner releases each slice
buf.release()                       // caller's reference to the original buffer
```

### Responsibility for `release()`

The last holder of a reference calls `release()`:

- For a buffer obtained via `allocator.allocate()`, the last consumer releases.
- For an additional reference created via `buf.retain()`, the creator of that reference releases.
- For a slice created via `allocator.slice(src, offset, length)`, the slice owner releases. The `src` reference is managed internally by the allocator.

The reference count starts at 1 after `allocate()`, increments with each `retain()`, and decrements with each `release()`. The buffer is freed (or returned to a pool) when the count reaches zero.

### `close()` vs `release()`

- `release()` — decrements the refcount; frees only when the count reaches zero. The normal path.
- `close()` — ignores the refcount and frees immediately. Rarely needed.

`close()` behaviour is platform-dependent:

| Platform | `close()` behaviour |
|---|---|
| Native | Frees via `nativeHeap.free`, ignoring the refcount |
| JVM | No-op (GC-managed) |
| JS | No-op (GC-managed) |

`close()` is intended for teardown paths (e.g., engine shutdown with buffers still outstanding in a pipeline). Normal lifecycle management is handled by `release()` alone.

## Typical usage patterns

### Pattern 1: Transport / Channel write (retain-on-input)

`channel.write(buf)` **retains internally in the transport**. The caller must explicitly release its own reference (layer 2 model).

```kotlin
val buf = allocator.allocate(128)
buf.writeAscii("hello", 0, 5)
channel.write(buf)      // transport retains (refCount 2); caller's readerIndex consumed
channel.flush()
buf.release()            // caller releases its reference (transport releases its retain after flush)
```

Forgetting `buf.release()` leaks memory. After `channel.write`, the caller may keep writing into the same buffer (if writable space remains) and submit it again.

### Pattern 2: Transport / Channel read (non-transfer)

`channel.read(buf)` does not transfer ownership. The caller allocates the buffer, hands it to the engine for fill, and releases it.

```kotlin
val buf = allocator.allocate(8192)
val n = channel.read(buf)
processData(buf)
buf.release()
```

### Pattern 3: Pipeline handler saves `msg` in a field (retain required)

In the pipeline layer (ownership transfer), when a handler keeps `msg` in a field, call `retain()` before storing and release on teardown.

```kotlin
class DelayedEcho : InboundHandler<IoBuf> {
    private var cached: IoBuf? = null

    override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
        cached = msg.retain()        // handler's own reference (+1)
        ctx.propagateRead(msg)        // original reference transferred downstream
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        cached?.release()
        cached = null
    }
}
```

### Pattern 4: Pipeline fan-out to multiple downstream handlers (retain required)

`ctx.propagateRead(msg)` is an ownership transfer inside the pipeline, so fanning out to multiple downstreams requires calling `retain()` **before** each transfer. The last transfer receives the original reference, so no `retain()` is needed for it. For N propagations, `retain()` is called `N - 1` times.

```kotlin
override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
    ctx.propagateRead(msg.retain())   // +1 for primary downstream
    ctx.propagateRead(msg)             // original reference for secondary downstream
    // Each downstream handler releases exactly once.
}
```

At the Transport / Channel boundary `channel.write` is retain-on-input, so adding `buf.retain()` does not solve the index-consumption issue. To write the same payload to multiple channels, use the `readerIndex` save/restore or `slice` approach shown in the "When to call `retain()`" section.

## Typical bugs

| Bug | Symptom | Cause |
|---|---|---|
| Forgetting `release()` after `channel.write` | Memory leak (detectable via leak detection) | Misunderstanding retain-on-input as transfer. The caller's own reference must be released. |
| Missing `release()` in the pipeline layer | Memory leak | A handler neither called `msg.release()` nor `propagateRead` (one of the two is required). |
| Double `release()` | `IllegalStateException: Buffer already released` | Released `msg` after `propagateRead(msg)` had transferred ownership. |
| Use after release | Native segfault, JVM invalid data, JS silent corruption | Read or wrote a released buffer. |
| Pipeline-handler retain without release | Memory leak | Stored in a field with `retain()` but not released in teardown (`onInactive`). |
| Netty-style "transfer" code on the retain-on-input path | Caller-side leak or double-release | Not touching the buffer after `channel.write(buf)` in Netty style. keel requires `buf.release()`. |
| Expecting `channel.write` chain to fan out | Second and later writes see 0 bytes | `readerIndex` is consumed on the first call; reset it or use `slice`. |

When a refcount bug is suspected in tests, wrap the allocator in `TrackingAllocator` and call `assertNoLeaks()` at the end (see next section).

## Leak detection

### Two detectors

| Tool | Purpose | Platforms |
|---|---|---|
| `TrackingAllocator` | Detects count mismatches between allocation and release | All platforms |
| `LeakDetectingAllocator` | Reports the allocation call site (stack trace) for each leaked buffer | Native, JVM |

### Usage

```kotlin
val tracker = DefaultAllocator
    .withLeakDetection { msg -> fail(msg) }
    .withTracking()

// Run the test...

tracker.assertNoLeaks()  // throws if any buffer was not released
```

`withTracking()` is applied outermost because `assertNoLeaks()` lives on that layer.

### Explicitly triggering GC

`LeakDetectingAllocator` relies on GC to detect leaks. Tests must trigger GC explicitly:

- **Native**: `kotlin.native.runtime.GC.collect()`
- **JVM**: `System.gc()`, followed by `tracker.allocate(1).release()` to drain the `PhantomReference` queue
- **JS**: not required (V8 GC-managed; leak detection does not apply)

## BufferAllocator

A pluggable interface for `IoBuf` allocation. Each engine has a platform-appropriate default; `IoEngineConfig` allows overriding.

```kotlin
val engine = KqueueEngine(
    config = IoEngineConfig(
        allocator = SlabAllocator(bufferSize = 8192, maxPoolSize = 256),
    ),
)
```

### Comparison of the three implementations

| Implementation | Target | Pool structure | Thread safety |
|---|---|---|---|
| `DefaultAllocator` | All targets | No pool | Stateless |
| `PooledDirectAllocator` | JVM | Per-size Treiber stack (lock-free) | `AtomicReference` CAS |
| `SlabAllocator` | Native | Per-size `ArrayDeque` (LIFO) | Spin-lock (`AtomicReference<Boolean>`) |

### Role of `createForEventLoop()`

At EventLoop startup, the engine calls `allocator.createForEventLoop()` to obtain a per-thread allocator instance. Stateless allocators return `this`; pooled implementations return a fresh instance so the pool is confined to a single thread and needs no atomic operations on the hot path. The parent allocator (the instance passed to `IoEngineConfig`) is used only for size-class registration at startup; per-EventLoop children perform the actual allocations.

### `DefaultAllocator`

A stateless implementation that allocates fresh on every call. It simply invokes `createDefaultIoBuf(capacity)` with no pooling. `createForEventLoop()` returns `this`. Used as test / fallback / JS default.

- **`wrapBytes`**: returns `null` (zero-copy wrapping not supported).
- **`slice`**: copy-based. Allocates a fresh buffer via `allocate(length)` and copies the source contents via `copyTo`, so the slice is independent of the source (no `retain` on source).

### `PooledDirectAllocator` (JVM)

Holds a Treiber stack per size class and operates on the pool lock-free. The stack head is an `AtomicReference<DirectIoBuf?>`; pool entries are linked intrusively via `IoBuf.nextLink`.

- **`allocate`**: CAS-pop from the pool; on miss, fresh `ByteBuffer.allocateDirect(capacity)`. The returned buffer's deallocator is set to `returnToPool`.
- **`release` (refCount reaches 0)**: the deallocator CAS-pushes onto the stack. If the pool is full (`maxSlots` exceeded), the push is abandoned and `buf.close()` is invoked instead.
- **`registerPoolSize(size, maxSlots)`**: lazy registration. If the total-memory budget (`maxTotalBytes`, default 251 KiB) is exceeded, `maxSlots` is automatically reduced. Duplicate registrations are no-ops.
- **`createForEventLoop()`**: returns a new instance with the parent's size classes propagated, per-pool limit reduced to `LOCAL_POOL_SLOTS = 8` (from the parent's default of 16).
- **`wrapBytes`**: zero-copy via `ByteBuffer.wrap(bytes, offset, length)`, returned as `DirectIoBuf.wrapExternal`. The backing array is caller-owned and must not be mutated until release.
- **`slice`**: zero-copy via `ByteBuffer.duplicate().slice()`. Retains `source` and installs a deallocator that releases `source` when the slice is released.

### `SlabAllocator` (Native)

Holds an `ArrayDeque<NativeIoBuf>` per size class as a LIFO pool. The whole pool `HashMap` is protected by a spin-lock (`AtomicReference<Boolean>` with CAS).

- **`allocate`**: `removeLast()` under the spin-lock; on miss, fresh `NativeIoBuf(capacity)` via `nativeHeap`. Deallocator is set to `returnToPool`.
- **`release` (refCount reaches 0)**: deallocator `addLast()` under the spin-lock. If the pool is full, `buf.close()` frees via `nativeHeap.free`.
- **`registerPoolSize(size, maxSlots)`**: lazy, budget-aware (`maxTotalBytes`, default 256 KiB). Duplicate check and insert happen atomically under the spin-lock.
- **`createForEventLoop()`**: returns a new instance with parent's size classes and `LOCAL_POOL_SLOTS = 8`.
- **`wrapBytes`**: zero-copy via a pinned `ByteArray` + `CPointer`, returned as `NativeIoBuf.wrapExternal`. The deallocator unpins on release.
- **`slice`**: zero-copy via pointer arithmetic. Retains `source` and installs a deallocator that releases `source` when the slice is released.

### Shared design principles

- **Minimal pool-hit cost**: `PooledDirectAllocator` uses CAS only; `SlabAllocator` uses a spin-lock only. Neither triggers heap allocation on a pool hit.
- **Graceful degradation under budget limits**: `registerPoolSize` auto-reduces `maxSlots` to keep within `maxTotalBytes`.
- **Fallback on unregistered sizes**: requesting a size that was never registered yields no pool; allocation always falls back to a fresh buffer (functionally correct, performance-degraded).

### io_uring engine specifics

The io_uring engine's inbound read path does not use `BufferAllocator`. It surfaces kernel-managed `ProvidedBufferRing` slots as `RingBufferIoBuf`. Other read paths and all write paths use the configured allocator.

## Per-platform implementation

The concrete `IoBuf` implementation differs per platform. All of them share the same policy: refcounts are **non-atomic `Int`** (single-EventLoop assumption), and `writeByte` / `readByte` skip bounds checks to keep the hot path thin. Only bulk operations (array-based read/write, `copyTo`) perform bounds checks.

### Comparison of the four implementations

| Implementation | Target | Backing storage | `close()` behaviour | External wrap support |
|---|---|---|---|---|
| `DirectIoBuf` | JVM | `ByteBuffer.allocateDirect(capacity)` | No-op (GC-managed) | `wrapExternal(buffer, bytesWritten)` |
| `NativeIoBuf` | Native | `nativeHeap.allocArray<ByteVar>(capacity)` | Invokes `nativeHeap.free`; `freed` flag for idempotency | `wrapExternal(ptr, capacity, bytesWritten, deallocator)` |
| `TypedArrayIoBuf` | JS | `Int8Array(capacity)` (V8 heap) | No-op (GC-managed) | `wrapExternal(array, bytesWritten)` |
| `RingBufferIoBuf` | io_uring engine | `ProvidedBufferRing` slot (kernel-managed) | Intentionally leaks the slot (`AutoCloseable` compat only; the normal path is `release()`) | The class itself is wrap-only |

### `DirectIoBuf` (JVM)

- **Backing storage**: primary constructor allocates `ByteBuffer.allocateDirect(capacity)`. Capacity is an immutable field.
- **Refcount**: bare `Int refCount = 1` (non-atomic).
- **Release path**: decrements `refCount`; at zero, invokes the deallocator (if set) or `close()`. Double-release is guarded only by the `refCount > 0` check.
- **`close()` behaviour**: sets `refCount = 0` and otherwise lets the JVM GC reclaim the direct buffer. External wraps delegate cleanup to the deallocator callback.
- **`writeByte` / `readByte`**: `buf.put(writerIndex++, value)` / `buf.get(readerIndex++)`, no bounds check (hot-path thinning).
- **`writeByteArray` / `readByteArray`**: bounds-checked; use `ByteBuffer.put(src, offset, length)` / `get(dst)` for the bulk copy.
- **`compact` / `clear`**: `ByteBuffer.compact()` on the properly positioned view; `clear()` resets indices plus position/limit.
- **`copyTo`**: creates a duplicated view and uses `ByteBuffer.put` to transfer.
- **Engine accessor**: `unsafeBuffer: ByteBuffer` (property + extension), used for zero-copy NIO syscalls.
- **`wrapExternal`**: companion factory that wraps a pre-allocated `ByteBuffer`; initializes `writerIndex = bytesWritten`.

### `NativeIoBuf` (Native: Linux / macOS)

- **Backing storage**: primary constructor allocates via `nativeHeap.allocArray<ByteVar>(capacity)` and sets `ownsMemory = true`.
- **Refcount**: bare `Int refCount = 1` (non-atomic) plus a `freed: Boolean` flag that guards against double-free.
- **Release path**: decrements `refCount`; at zero, invokes the deallocator or `close()`.
- **`close()` behaviour**: `if (!freed)` guard, then `freed = true` and `refCount = 0`; calls `nativeHeap.free(ptr.rawValue)` only when `ownsMemory` is true. **Of the four implementations, this is the only one that performs an actual memory deallocation.**
- **`writeByte` / `readByte`**: `ptr[writerIndex++] = value` / `ptr[readerIndex++]`, no bounds check.
- **`writeByteArray` / `readByteArray`**: bounds-checked; pin + `memcpy(ptr + index, src, length)` for bulk copy.
- **`compact`**: no-op when `readerIndex == 0`; otherwise `memmove(ptr, ptr + readerIndex, readable)`.
- **`copyTo`**: when the destination implements `NativePointerAccess` (exposes `unsafePointer`), transfers directly with `memcpy`.
- **Engine accessor**: `unsafePointer: CPointer<ByteVar>` (interface member), used for POSIX syscalls (`read(2)` / `write(2)` / `writev(2)`).
- **`wrapExternal`**: companion factory that accepts a raw pointer, capacity, `bytesWritten`, and an optional deallocator. Initializes `ownsMemory = false`; `resetForReuse()` resets indices and clears the `freed` flag (preserving `ptr` and `ownsMemory`) so pools can reuse the wrapper.

### `TypedArrayIoBuf` (JS)

- **Backing storage**: `Int8Array(capacity)` allocated on the V8 heap; `capacity = array.length`.
- **Refcount**: bare `Int refCount = 1` (JS is single-threaded).
- **Release path**: decrements `refCount`; at zero, invokes the deallocator or `close()`.
- **`close()` behaviour**: sets `refCount = 0`; the `Int8Array` is reclaimed by V8's GC (effective no-op).
- **`writeByte` / `readByte`**: `buf.asDynamic()[writerIndex++] = value` / `(buf.asDynamic()[readerIndex++] as Int).toByte()`. The `asDynamic()` cast is required because Kotlin/JS IR mode does not compile typed-array indexing directly; the dynamic call lands on V8-native operations.
- **`writeByteArray` / `readByteArray`**: bounds-checked; element-wise loops via `asDynamic()`.
- **`compact`**: uses V8-native `Int8Array.copyWithin(0, readerIndex, writerIndex)`.
- **`copyTo`**: `destBuf.set(buf.subarray(readerIndex, ...), dest.writerIndex)`, V8's optimized typed-array bulk copy.
- **Engine accessor**: `unsafeArray: Int8Array` (property + extension), used for Node.js `net.Socket.write` and similar APIs.
- **`wrapExternal`**: companion factory that wraps a pre-allocated `Int8Array` and sets `writerIndex = bytesWritten`. No `ownsMemory` field (always false semantically).

### `RingBufferIoBuf` (io_uring engine)

This implementation is qualitatively different from the other three. It is a **wrap-only class that never goes through an allocator** — each slot is pre-allocated in a `ProvidedBufferRing` at startup, and the `IoBuf` instance is reused across CQE callbacks.

- **Backing storage**: a slot in the `ProvidedBufferRing`. The pointer is computed via `bufferRing.getPointer(bufId)` and cached in the constructor (avoiding per-access recomputation).
- **Refcount**: bare `Int refCount = 1`.
- **Lifecycle**: no `allocate` factory. Slot-sized instances are created once at source startup, and each CQE callback calls `reset()` (which resets the indices and `refCount = 1` while preserving `ptr` and the `bufferRing` reference). **Hot-path object allocation is zero.**
- **Release path**: when `refCount` reaches 0, the `onRelease(this)` callback fires, returning the slot to the ring.
- **`close()` behaviour**: sets `refCount = 0` but does NOT call `onRelease`. The slot is **intentionally leaked** — `close()` exists purely for `AutoCloseable` compatibility; the normal path is `release()`.
- **`writeByte` / `readByte` / bulk ops**: structurally identical to `NativeIoBuf` (`ptr[index++]` / `memcpy` / `memmove`).
- **Engine accessor**: `unsafePointer: CPointer<ByteVar>` (implements `NativePointerAccess`), used to submit SQEs (`io_uring_prep_recv`, etc.).
- **Platform-unique**: no hot-path allocation means the implementation can plug directly into a zero-copy read path once Fixed Buffers / MemoryOwner infrastructure lands.

### Shared design policies

- **Non-atomic `refCount`**: all implementations store it as a bare `Int` (no `@Volatile`). This is an intentional choice that rests on EL alignment; cross-thread sharing is a contract violation. See the "Thread safety contract" section earlier for the rationale and failure modes.
- **No bounds check on single-byte read/write**: an intentional trade-off for hot-path throughput. Boundary correctness is the caller's responsibility.
- **Bulk operations are bounds-checked**: methods that take a `length` (`writeByteArray` / `readByteArray` / `copyTo`) fail early on invalid arguments.
- **Engine-specific accessors**: each implementation exposes its platform-native memory primitive under an `unsafe*` name. These are not part of the `IoBuf` interface (they are inherently platform-specific).

## Large-payload optimization

This section describes a performance problem that arises when sending large responses via `keel-ktor-engine` and the automatic mechanism that resolves it. Ordinary Ktor handlers need not think about this, but understanding the behaviour helps when interpreting benchmark results or designing large-file / streaming-body paths.

### Background: role of `BufferedSuspendSink`

The write path for a Ktor application response looks like:

1. The handler calls `call.respondBytes(byteArray)` or `call.respondOutputStream { ... }`.
2. Ktor's write path reaches the `keel-ktor-engine` transport adapter.
3. The adapter writes through `BufferedSuspendSink` to the engine's transport.

`BufferedSuspendSink` internally holds an 8 KiB scratch buffer to coalesce small writes before forwarding to the transport. This is the same class of optimization as kotlinx-io's `BufferedSink`, designed to avoid many small `Channel.write` invocations.

### Problem: splitting overhead for large payloads

The issue arises when a single payload exceeds the scratch buffer. A naive implementation would split a 1 MiB response into 128 writes of 8 KiB each. Each chunk incurs:

- One `PendingWrite` struct allocation
- One flush listener (callback) allocation
- On Netty-backed JVM paths, one `ByteBuf` allocation
- One `memcpy` from scratch to the transport's owned buffer

This happens 128 times. On JVM the per-response GC pressure is substantial, and full-matrix benchmarks showed `/large` (100 KiB) throughput several times lower than `/hello` (13 bytes) before this optimization.

### Solution: bypassing the scratch via `DIRECT_WRITE_THRESHOLD`

`BufferedSuspendSink` routes writes of at least `DIRECT_WRITE_THRESHOLD` bytes past the scratch buffer, wrapping the caller's `ByteArray` as an `IoBuf` view and forwarding it directly to the transport. The threshold matches the scratch buffer size (`BUFFER_SIZE = 8192` in code), so any single write that would itself fill the scratch is worth skipping it.

```kotlin
// Inside a Ktor route:
call.respondBytes(small)   // < 8 KiB: copied through the scratch buffer
call.respondBytes(large)   // ≥ 8 KiB: wrapped as an IoBuf view, scratch skipped
```

The path is `BufferedSuspendSink.write → BufferAllocator.wrapBytes → IoBuf view → transport.write`.

**Caveat**: the wrapped `IoBuf` shares storage with the caller's `ByteArray`. The array must not be mutated until the next `flush()` completes. Normal `call.respondBytes(...)` usage does not expose this as a hazard.

### Platform support

The availability of `wrapBytes` determines whether the optimization kicks in.

| Platform | Zero-copy optimization | Implementation |
|---|---|---|
| JVM | Enabled | `PooledDirectAllocator.wrapBytes` via `ByteBuffer.wrap(bytes, offset, length)` |
| Native | Enabled | `SlabAllocator.wrapBytes` via pinned `ByteArray` + `CPointer` |
| JS | Not available | `DefaultAllocator.wrapBytes` returns `null`; falls back to the chunked copy path |

JS lacks support because V8's `Int8Array`-based memory model has no primitive equivalent to zero-copy ByteArray wrapping. Under V8 GC management the multiple small allocations cause less visible regression than on Native or JVM.

### Significance

Approximate allocation counts for a 1 MiB response:

| Path | `PendingWrite` | flush listener | Netty `ByteBuf` (JVM) | scratch memcpy |
|---|---|---|---|---|
| Scratch-routed (chunked) | 128 | 128 | 128 | 128 |
| Zero-copy (wrap) | 1 | 1 | 1 | 0 |

On JVM, the GC-pressure difference is large. Full-matrix benchmarks have recorded 10–30% throughput improvements on the `/large` path once the zero-copy write is enabled (details in `benchmark/results-summary/`).

## Comparison with other buffer APIs

Most developers approaching keel already know a buffer API from another networking library. The table below compares `IoBuf` with six representative implementations.

| Feature | keel `IoBuf` | Netty `ByteBuf` | SwiftNIO `ByteBuffer` | tokio `bytes::Bytes`/`BytesMut` | NIO `ByteBuffer` | kotlinx.io `Buffer` |
|---|---|---|---|---|---|---|
| Reference counting | Yes (non-atomic) | Yes (atomic) | Swift CoW (value type) | Yes (`Arc`, atomic) | No (GC) | No (GC) |
| Ownership model | Transfer | Transfer | Value type + CoW | Move (Rust default) | N/A | N/A |
| Reader / writer index | Separate | Separate | Separate | Single cursor + `split_to` | Shared `position` / `limit` | Segmented |
| Off-heap memory | Yes (JVM/Native) | Yes (pooled direct) | N/A (Swift-managed) | N/A (Rust-managed) | Optional (`allocateDirect`) | No |
| Target platform | All KMP targets | JVM | Apple platforms (Swift) | All Rust targets | JVM | KMP |
| Zero-copy slice | `allocator.slice(...)` | `slice()` / `retainedSlice()` | `slice()` / `readSlice(n)` | `Bytes::slice` / `split_to(n)` | `slice()` | Segment reference |
| Compaction | `compact()` | `discardReadBytes()` | `discardReadBytes()` | Implicit via `split` | `compact()` | Segment rebalance |

**Design families at a glance**:

- **keel `IoBuf` / Netty `ByteBuf` / SwiftNIO `ByteBuffer`**: dual-index model (separate reader/writer) + reference counting (or CoW). SwiftNIO adapted Netty's design to Swift's value semantics via CoW; keel specializes Netty's design for KMP's single-thread EventLoop with a non-atomic refcount.
- **tokio `bytes`**: a Rust-idiomatic split into `Bytes` (immutable, shared via `Arc`) and `BytesMut` (mutable, exclusive). The `split_to` / `split_off` operations correspond to Netty's slice + retain.
- **NIO `ByteBuffer`**: single-cursor (`position` / `limit`) + GC-managed. A low-level primitive whose `flip()` / `clear()` mental model is idiosyncratic.
- **kotlinx.io `Buffer`**: a linked list of segments, GC-managed. Complementary to `IoBuf` — keel typically uses `IoBuf` at the transport layer and `kotlinx.io Buffer` at the codec layer.

**Takeaways**:

- **Netty users**: the pipeline layer (handler-to-handler ownership transfer) is the same mental model. The difference is that `channel.write(buf)` is **retain-on-input** rather than transfer (Netty), so in keel the caller must explicitly call `buf.release()`. Other differences are "non-atomic refcount" and "fixed capacity".
- **SwiftNIO users**: the API shape is close. The difference is explicit `retain` / `release` instead of value type + CoW; forgetting `release()` leaks (SwiftNIO delegates refcounting to the language).
- **tokio `bytes` users**: `IoBuf` is closer to `BytesMut`, but relies on `retain()` for refcount sharing rather than `split` to create independent handles.
- **NIO users**: separate reader and writer indices remove the need for `flip()`. In return, `release()` is required at end-of-life.
- **kotlinx.io users**: `IoBuf` is lower-level (single buffer, no segment list) and offers predictable off-heap memory behaviour. They compose well: codec layer uses `kotlinx.io Buffer`, transport layer uses `IoBuf`.

Adjacent APIs not included in the table: .NET `Memory<byte>` / `IMemoryOwner<byte>` (pool-based, no refcount), Go `bytes.Buffer` (GC-managed, growable), libuv `uv_buf_t` (C struct: base + len view only). keel has no direct relationship with these, so they are omitted from the comparison.

## See also

- `IoBuf` KDoc: [`keel-io/.../buf/IoBuf.kt`](https://github.com/fukusaka/keel/blob/main/keel-io/src/commonMain/kotlin/io/github/fukusaka/keel/buf/IoBuf.kt)
- `BufferAllocator` KDoc: [`keel-io/.../buf/BufferAllocator.kt`](https://github.com/fukusaka/keel/blob/main/keel-io/src/commonMain/kotlin/io/github/fukusaka/keel/buf/BufferAllocator.kt)
- Netty reference counting: [Reference Counted Objects](https://netty.io/wiki/reference-counted-objects.html)
