---
sidebar_position: 3
---

# IoBuf and BufferAllocator

## Summary

`IoBuf` is keel's byte buffer type. Two rules cover 95% of usage:

1. **Writes transfer ownership.** After `channel.write(buf)` (or `sink.write`, or `ctx.propagateWrite`), the buffer is gone — do not touch it, do not `release()` it, do not inspect its indices. The engine releases it once the bytes have been sent.
2. **Reads keep ownership.** You allocate a buffer, pass it to `channel.read(buf)` so the engine fills it, then you read from it and `release()` it when done.

That's it. Everything else — `retain()`, fan-out, slicing, pool behaviour — only matters when you step outside those two rules on purpose.

```kotlin
// Write
val buf = allocator.allocate(128)
buf.writeAscii("hello", 0, 5)
channel.write(buf)    // ownership transferred; do not touch buf afterwards
channel.flush()

// Read
val buf = allocator.allocate(8192)
val n = channel.read(buf)
processData(buf)
buf.release()         // caller-owned; release when done
```

**If you're coming from Netty**: the model is the same as Netty's `ByteBuf` + `ctx.writeAndFlush(buf)` — write transfers, call `retain()` to keep. keel's differences are cosmetic (non-atomic `refCount`, fixed capacity, platform-native backing).

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

The ownership model is **ownership transfer for writes, non-transfer for reads** — a single rule with one inverse operation. Almost all reference-count bugs come from missing this distinction, so we spend a bit of space on it here.

### The core rule

> Any API that takes a filled `IoBuf` and sends it somewhere **takes over the reference**. The caller must not touch the buffer afterwards.

Concretely, these APIs all behave this way:

| Category | APIs |
|---|---|
| Transport-layer write | `Channel.write(buf)` / `IoTransport.write(buf)` / `SuspendSink.write(buf)` |
| Pipeline-layer write | `ctx.propagateWrite(msg)` |
| Pipeline-layer inbound propagation | `ctx.propagateRead(msg)` / `transport.onRead(buf)` callback |
| User-event propagation | `ctx.propagateUserEvent(evt)` (when `evt` carries an `IoBuf`) |

"Do not touch afterwards" means: no `readByte` / `writeByte`, no `release()`, no reading `readerIndex` or `writerIndex`. The engine / next handler releases the buffer once it's done with it.

### Writes

```kotlin
val buf = allocator.allocate(128)
buf.writeAscii("hello", 0, 5)
channel.write(buf)    // transferred; buf is gone from the caller's POV
channel.flush()
// Do not call buf.release() here — the transport does it.
```

If you forget and add `buf.release()` after the write, you will get a double-release error (`IllegalStateException: Buffer already released`) the next time the transport tries to release.

### Reads are the inverse

Reads don't transfer — they're the **inverse operation**: the caller allocates an empty buffer and lends it to the engine for filling. Ownership never leaves the caller.

```kotlin
val buf = allocator.allocate(8192)
val n = channel.read(buf)   // engine fills buf; caller still owns it
processData(buf)
buf.release()               // caller releases when done
```

This isn't really a "third ownership model" — it's just that `read` is an inverted `write`. The caller is the sink for the bytes, and the engine is the source, so the ownership flow goes the other way.

### Engine-delivered reads (advanced)

Some push-model engines — NWConnection, Netty, Node.js — already have received data sitting in their own buffers, so it's wasteful to have the caller allocate a separate one just to be filled. For that path there is `OwnedSuspendSource.readOwned(): IoBuf?`:

```kotlin
val source: OwnedSuspendSource = channel.asOwnedSuspendSource()
val buf = source.readOwned() ?: return   // null == EOF
// The engine handed us a ready-made buffer; we own it now.
processData(buf)
buf.release()                              // release when done
```

The mental model stays the same: **whoever ends up holding the buffer releases it when done**. The only thing that changes from `channel.read(buf)` is *where the buffer came from*:

- `channel.read(buf)` — you allocated it and lent it to the engine; you release it afterwards.
- `readOwned()` — the engine allocated it and returned it to you; you release it afterwards.

Treat `readOwned` as a buffer-returning function: receive → use → release. Concretely, it still counts as a new reference in the caller's hands, so the API classification below groups it under "New-reference APIs" alongside `allocator.allocate(...)`.

`OwnedSuspendSource` is an engine-integration interface — Ktor and the codec layers don't touch it directly. First-time keel users typically only deal with `Channel.read(buf)`, so feel free to skip this section until you actually need zero-copy push-mode reads.

### API ownership at a glance

**Transfer (the caller gives up the reference)**

| API | Who releases |
|---|---|
| `Channel.write(buf)` / `IoTransport.write(buf)` / `SuspendSink.write(buf)` | Transport, after flush completes |
| `ctx.propagateWrite(msg)` / `ctx.propagateRead(msg)` | Downstream/upstream handler (final consumer) |
| `transport.onRead(buf)` callback → pipeline HEAD | Pipeline chain's terminal handler |
| `onRead(ctx, msg)` / `onReadTyped(ctx, msg)` | The handler itself (typically `try/finally`) |

**Non-transfer (the caller keeps the reference)**

| API | Caller's responsibility |
|---|---|
| `Channel.read(buf)` / `IoTransport.read(buf)` | Allocated the buffer → releases when done |
| `buf.readByte()` / `writeByte()` / `getByte(i)` / `readByteArray(...)` / `writeByteArray(...)` | Owns the buffer; indices advance, ownership unchanged |
| `buf.copyTo(dst, length)` | Source + dst both caller-owned |
| `buf.compact()` / `clear()` | Ownership unchanged |

**New-reference APIs (caller receives ownership)**

| API | Initial refCount | Who releases |
|---|---|---|
| `allocator.allocate(size)` | 1 | The final consumer |
| `allocator.wrapBytes(bytes, offset, length)` | 1 (or `null` on JS) | The final consumer (the input `ByteArray` stays caller-owned) |
| `allocator.slice(src, offset, length)` | 1 (independent of `src`) | Whoever owns the slice (`src` is tracked internally) |
| `buf.retain()` | existing + 1, same instance | Whoever added the reference |
| `OwnedSuspendSource.readOwned()` | 1 (or `null` on EOF) | Caller (engine transfers via return value — see "Engine-delivered reads") |

### Transport-level indices: not advanced by the engine

After `channel.write(buf)` returns, `buf.readerIndex` and `buf.writerIndex` are **unchanged** — the engine captures them as a snapshot in its pending-writes queue and does not mutate the live buffer. This matches Netty's `ChannelOutboundBuffer` semantics.

The caller can't observe this because the caller isn't supposed to touch the buffer anyway. But if a holder of a `retain()` reference inspects the indices, they see the buffer in the state it had at write time — which is the useful, non-surprising answer.

### When to use `retain()`

`retain()` only matters when you deliberately step outside the "transfer" rule. Three realistic cases:

**(1) Fan-out: send the same buffer to multiple sinks**

```kotlin
channel1.write(buf.retain())   // +1 for channel1
channel2.write(buf.retain())   // +1 for channel2
channel3.write(buf)             // last one takes the original ref
```

For N sinks, call `retain()` on `N - 1` of them. Netty users will recognize this pattern.

**(2) Hold a buffer for later processing**

```kotlin
class DelayedEcho : InboundHandler<IoBuf> {
    private var cached: IoBuf? = null

    override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
        cached = msg.retain()       // +1 for this handler
        ctx.propagateRead(msg)       // original ref transferred downstream
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        cached?.release()            // balances the retain() above
        cached = null
    }
}
```

**(3) Keep a buffer alive across a suspension before writing**

```kotlin
suspend fun relay(src: Channel, dst: Channel) {
    val buf = allocator.allocate(8192)
    try {
        val n = src.read(buf)                // non-transfer
        if (n > 0) dst.write(buf.retain())  // transfer one ref, keep our own
        // buf still usable here
    } finally {
        buf.release()                         // our ref
    }
}
```

If you didn't `retain()` in the `dst.write` line, the buffer would be gone after `dst.write` returns and the `finally` block's `release()` would double-release. Always match every "keep across boundary" with a `retain()`.

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

## Common bugs

| Bug | Symptom | Cause |
|---|---|---|
| Extra `release()` after `channel.write(buf)` | `IllegalStateException: Buffer already released` on the next transport operation | The transport released the buffer after flush — calling `release()` yourself double-releases. |
| Missing `release()` in a handler that doesn't propagate | Memory leak (detectable via `TrackingAllocator`) | A handler received `msg` and neither `propagateRead(msg)` nor `msg.release()`. One of the two is required. |
| Use after write | Native segfault, JVM invalid data, JS silent corruption | Touched the buffer after `channel.write(buf)` (read a byte, checked `readerIndex`, etc.). |
| Fan-out without `retain()` | First write works, second sees `readableBytes == 0` or throws | The first `channel.write(buf)` consumed ownership; the second receives a released buffer. Use `buf.retain()` on all but the last. |
| Handler stores `msg` without retaining | Use-after-release in later event | The transferred msg was released downstream; the handler's stored reference is stale. `retain()` before storing. |

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

## Backing ownership strategies

Orthogonal to the ref-count flow (who calls `retain` / `release`) is the
question of **what happens to the backing memory when refcount reaches
zero** — free the native allocation, return to a pool, unpin an
externally-wrapped array, return a kernel-managed slot, …

keel expresses this via [`IoBufMemoryOwner`](https://github.com/fukusaka/keel/blob/main/keel-io/src/commonMain/kotlin/io/github/fukusaka/keel/buf/IoBufMemoryOwner.kt),
a plain interface carried as an immutable `val memoryOwner` on every
`IoBuf`. `IoBuf.release()` invokes `memoryOwner.release(this)` exactly
once when the refcount reaches zero.

### Strategy taxonomy

| Strategy | Backing | Extra state | Release behaviour |
|---|---|---|---|
| `HeapOwner` (singleton) | `nativeHeap` / `ByteBuffer.allocateDirect` / `Int8Array` | none | `nativeHeap.free` on Native; GC-managed on JVM/JS (via `HeapManagedBacking` marker) |
| `PoolOwner` | platform backing, managed by a pool | `returnToPool` lambda | push back to the Treiber stack / LIFO slot |
| `SliceOwner` | sub-range of a parent `IoBuf` | `parent` ref | `parent.release()` |
| `ExternalWrapOwner` | caller's pinned `ByteArray` or pre-allocated direct `ByteBuffer` | `unpin` lambda | drop the pin / external hold |
| `RingSlotOwner` (engine-io-uring) | `ProvidedBufferRing` kernel slot | `ring`, `bufId` | return slot to the ring (`multishot recv`) |
| `FixedBufferOwner` (engine-io-uring, future) | `io_uring_register_buffers` region | `registry`, `bufIndex` | return to the fixed-buffer registry (enables `READ_FIXED` / `WRITE_FIXED`) |
| `NettyByteBufOwner` (engine-netty, future) | Netty pooled `ByteBuf` | `nettyBuf` ref | delegate to `nettyBuf.release()` |

The interface is deliberately **plain, not sealed**: engine modules or
downstream consumers can add their own owner variants without
modifying keel-io (for example, a custom engine could introduce a
`ShmOwner` for POSIX shared memory). Engines that need to distinguish
strategies on the hot path (io_uring picking `WRITE_FIXED` vs `WRITE`)
use a direct type check: `owner is FixedBufferOwner` gives both the
identification and the strategy-specific state (`bufIndex`) in one
step.

### When this matters in practice

Ordinary callers never notice: `allocator.allocate(size)` returns an
`IoBuf` with the correct owner for the backing storage, and
`buf.release()` does the right thing. Engines do not query owners on
the common path either; only specialised paths (fixed-buffer io_uring,
leak-tracking decorators) do.

Where the taxonomy pays off:

- **Slice safety**: `SliceOwner` stores only a parent ref, not a
  `bufIndex`. A slice can therefore never be routed to `WRITE_FIXED`
  by accident — the type check that would pick FIXED I/O simply
  fails, and the write falls back to the regular path.
- **Pool return path**: `PoolOwner` captures the single pool instance
  and is reused across all allocations from that pool, avoiding the
  per-allocation lambda closures of the older `deallocator`
  approach.
- **Leak detection**: `TrackingAllocator` and `LeakDetectingAllocator`
  wrap the owner in place (via the internal `PoolableIoBuf`
  interface) so every release goes through a counting or
  stack-recording decorator before reaching the real owner.

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

- **Netty users**: the mental model is identical — both at the pipeline layer (handler-to-handler) and at the `Channel.write` boundary, ownership transfers to the callee. Call `buf.retain()` before the write if you need to keep a reference. The remaining differences are implementation details: keel uses a non-atomic `refCount` (single-EventLoop assumption) and a fixed capacity (no dynamic resize).
- **SwiftNIO users**: the API shape is close. The difference is explicit `retain` / `release` instead of value type + CoW; forgetting `release()` leaks (SwiftNIO delegates refcounting to the language).
- **tokio `bytes` users**: `IoBuf` is closer to `BytesMut`, but relies on `retain()` for refcount sharing rather than `split` to create independent handles.
- **NIO users**: separate reader and writer indices remove the need for `flip()`. In return, `release()` is required at end-of-life.
- **kotlinx.io users**: `IoBuf` is lower-level (single buffer, no segment list) and offers predictable off-heap memory behaviour. They compose well: codec layer uses `kotlinx.io Buffer`, transport layer uses `IoBuf`.

Adjacent APIs not included in the table: .NET `Memory<byte>` / `IMemoryOwner<byte>` (pool-based, no refcount), Go `bytes.Buffer` (GC-managed, growable), libuv `uv_buf_t` (C struct: base + len view only). keel has no direct relationship with these, so they are omitted from the comparison.

## See also

- `IoBuf` KDoc: [`keel-io/.../buf/IoBuf.kt`](https://github.com/fukusaka/keel/blob/main/keel-io/src/commonMain/kotlin/io/github/fukusaka/keel/buf/IoBuf.kt)
- `BufferAllocator` KDoc: [`keel-io/.../buf/BufferAllocator.kt`](https://github.com/fukusaka/keel/blob/main/keel-io/src/commonMain/kotlin/io/github/fukusaka/keel/buf/BufferAllocator.kt)
- Netty reference counting: [Reference Counted Objects](https://netty.io/wiki/reference-counted-objects.html)
