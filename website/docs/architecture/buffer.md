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

**If you're coming from Netty**: the model is the same as Netty's `ByteBuf` + `ctx.writeAndFlush(buf)` — write transfers, call `retain()` to keep. keel's differences are cosmetic (fixed capacity, platform-native backing per KMP target).

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

`IoBuf` splits its thread-safety guarantees by API category — the same split Netty `ByteBuf` makes:

- **Lifecycle (`retain()` / `release()` / `close()`) is thread-safe.** The reference count is atomic, and the update protocol is a CAS loop that checks the current count before bumping it: a retain or release that loses to a concurrent update retries on the fresh count, and a call that observes an already-released buffer throws `IllegalStateException` without perturbing the count. Any thread that holds a reference may retain or release without coordination.
- **Content access (read\*/write\*, `readerIndex` / `writerIndex`, `clear()`) is NOT thread-safe.** At most one thread may access a buffer's content at any instant. Handing content access to another thread requires a happens-before edge (EventLoop `dispatch`, Netty `EventLoop.execute`, NWConnection `dispatch_queue_async`, a channel send, …); after the handoff, the receiving thread becomes the sole content accessor.

In practice keel keeps both content access and lifecycle on the EventLoop that owns the channel:

- All engines align `ioDispatcher` with their worker EventLoop. Transport-layer operations always run on the EL.
- Push-based engines (NWConnection / Netty) align to their EL thread via `NwConnectionQueueDispatcher` / `NettyEventLoopDispatcher`. Callbacks delivered through `SuspendBridgeHandler` resume on the same EL.
- `Channel.write(buf)` queues the buffer internally; dequeue + flush + release all run on the same EL.

The atomic lifecycle is what makes the remaining off-EL patterns safe: a consumer draining a channel from a non-EventLoop coroutine, or a fan-out that releases a buffer on another thread, can retain / release correctly — it must only avoid touching the buffer's *content* concurrently with another accessor.

### How the contract gets broken

The lifecycle guarantee does not extend to content. The following code is still a violation:

```kotlin
override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
    val buf = msg as IoBuf
    coroutineScope.launch(Dispatchers.Default) {
        // Runs on the Default pool while downstream handlers may still touch the buffer:
        processAsync(buf)     // concurrent content access — data race
        buf.release()          // the release itself is thread-safe, but doesn't fix the race
    }
}
```

- Reading or writing a buffer from `withContext(Dispatchers.IO)` while the EventLoop (or a downstream handler) still accesses it.
- Handing a buffer to another thread through a plain field or collection without a happens-before edge — the receiver may observe stale content or half-written indices.
- Two threads writing into the same buffer "cooperatively".

Symptoms are those of any data race: torn or stale bytes, and on Native — where a racing content access can trail a concurrent free — a segfault. Refcount mistakes, by contrast, fail loudly: double-release and retain-after-release throw `IllegalStateException` instead of corrupting the count.

### Contrast with Netty's atomic approach

Earlier keel versions stored the refcount as a plain non-atomic `Int`, betting that every cross-thread ownership transfer rides a happens-before edge. That bet lost on the GCD-backed NWConnection engine: GCD serialises one connection's callbacks but migrates them across OS worker pthreads, and the resulting cross-worker refcount race surfaced as intermittent `Buffer already released` crashes under HTTPS load. The refcount has been atomic since — the same conclusion Netty reached with `AtomicIntegerFieldUpdater`. The fetch-and-add cost fires only on lifecycle transitions, never per byte or per read.

| | keel `IoBuf` | Netty `ByteBuf` |
|---|---|---|
| `refCount` | Atomic (`AtomicInt`, CAS loop) | `AtomicIntegerFieldUpdater` (CAS) |
| Cross-thread retain / release | Allowed from any reference holder | Allowed |
| Cross-thread content access | Contract violation (requires a happens-before handoff) | Same contract |
| Double-release / retain-after-release | Throws `IllegalStateException` | Throws `IllegalReferenceCountException` |

The remaining differences are cosmetic: fixed capacity (no dynamic resize) and platform-native backing per KMP target.

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
- `clear()` resets both indices to 0, making the whole buffer writable again. There is no `compact()` — `IoBuf` is fixed-capacity by design; instead of shifting bytes, a consumed buffer is released back to the pool and a fresh one is allocated.

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
| Pipeline-layer inbound propagation | `ctx.propagateRead(msg)` / `Pipeline.notifyRead(msg)` |
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
val source: OwnedSuspendSource = ...     // engine-provided (see below)
val buf = source.readOwned() ?: return   // null == EOF
// The engine handed us a ready-made buffer; we own it now.
processData(buf)
buf.release()                              // release when done
```

The mental model stays the same: **whoever ends up holding the buffer releases it when done**. The only thing that changes from `channel.read(buf)` is *where the buffer came from*:

- `channel.read(buf)` — you allocated it and lent it to the engine; you release it afterwards.
- `readOwned()` — the engine allocated it and returned it to you; you release it afterwards.

Treat `readOwned` as a buffer-returning function: receive → use → release. Concretely, it still counts as a new reference in the caller's hands, so the API classification below groups it under "New-reference APIs" alongside `allocator.allocate(...)`.

`OwnedSuspendSource` is an engine-integration interface — engines expose it through their pipeline bridge (`SuspendBridgeHandler` implements it), and `channel.asBufferedSuspendSource()` consumes it under the hood on push-model channels. Ktor and the codec layers don't touch it directly. First-time keel users typically only deal with `Channel.read(buf)`, so feel free to skip this section until you actually need zero-copy push-mode reads.

### API ownership at a glance

**Transfer (the caller gives up the reference)**

| API | Who releases |
|---|---|
| `Channel.write(buf)` / `IoTransport.write(buf)` / `SuspendSink.write(buf)` | Transport, after flush completes |
| `ctx.propagateWrite(msg)` / `ctx.propagateRead(msg)` | Downstream/upstream handler (final consumer) |
| `Pipeline.notifyRead(msg)` → pipeline HEAD | Pipeline chain's terminal handler |
| `onRead(ctx, msg)` / `onReadTyped(ctx, msg)` | The handler itself (typically `try/finally`) |

**Non-transfer (the caller keeps the reference)**

| API | Caller's responsibility |
|---|---|
| `Channel.read(buf)` | Allocated the buffer → releases when done |
| `buf.readByte()` / `writeByte()` / `getByte(i)` / `readByteArray(...)` / `writeByteArray(...)` | Owns the buffer; indices advance, ownership unchanged |
| `buf.copyTo(dst, length)` | Source + dst both caller-owned |
| `buf.clear()` | Ownership unchanged |

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
class DelayedEcho : TypedInboundHandler<IoBuf>(IoBuf::class) {
    private var cached: IoBuf? = null

    override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
        cached = msg.retain()       // +1 for this handler
        ctx.propagateRead(msg)       // original ref transferred downstream
        // autoRelease (default true) skips its release here because the
        // exact same object was propagated; the handler's own reference
        // stays alive through the retain() above.
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

A pluggable interface for `IoBuf` allocation. Each engine has a platform-appropriate default (via `defaultAllocator()`); `IoEngineConfig` allows overriding — for example to wire in a stats counter or a lifecycle listener:

```kotlin
val engine = KqueueEngine(
    config = IoEngineConfig(
        allocator = SlabAllocator(),   // Native pooled allocator with the default size-class ladder
    ),
)
```

### Comparison of the three implementations

| Implementation | Target | Pooling | Freelist concurrency |
|---|---|---|---|
| `DefaultAllocator` | All targets | None (fresh allocation per call) | Stateless |
| `SlabAllocator` | Native | Chunk-based `PooledAllocator` | Spin-lock per size class + MPSC cross-thread return queue |
| `PooledDirectAllocator` | JVM | Chunk-based `PooledAllocator` | Mutex (`ReentrantLock`) per size class |

### Role of `createChild()`

Engines call `allocator.createChild()` to obtain an owned child allocator whose lifetime they control — one per EventLoop thread for the thread-pinned engines (epoll / kqueue / NIO / io_uring), once per engine where there is no per-thread split (NWConnection, Node.js). Pool-based parents give each child its own size-class freelist cache while all children share the parent's chunk arena; the parent tracks its children and cascade-closes them on `close()`. The parent instance passed to `IoEngineConfig` performs no hot-path allocation for these engines, though nothing stops an engine from allocating through it — the in-memory engine used in tests copies through it on every flush. A sibling, `createUntrackedChild()`, produces a child the caller must close itself — for unbounded, churning populations such as one allocator per accepted connection. What it hands back is not necessarily new: the default chain ends at `createChild()`'s `this`, and a wrapper forwards its delegate's answer outward, so a caller that must not close somebody else's allocator should be given one that makes real children rather than try to tell them apart.

### What an engine requires of an allocator

An engine hands read-buffer memory straight to the kernel through an unchecked cast — to `NativePointerAccess` on the Native targets, `NioByteBufferBacking` on the JVM, `TypedArrayIoBuf` on JS. A custom allocator used with one must hand out buffers carrying that backing, and so must its children, since a child is what an engine reads through. The epoll and kqueue engines ask once while being built and refuse to start otherwise, naming the allocator; on the others the same mistake surfaces later, in whatever form that engine's read path gives it. The Netty engine is exempt: it allocates from each channel's own `ByteBufAllocator` and consults the configured one for its lifecycle listener alone.

### `DefaultAllocator`

A stateless implementation that allocates fresh on every call, with no pooling. `createChild()` returns `this`. Used as test / fallback / JS default.

- **`wrapBytes`**: returns `null` (zero-copy wrapping not supported).
- **`slice`**: zero-copy. Retains the source and returns a platform-native view whose `SliceOwner` releases the source when the slice itself is released.

### Chunk-based pooling (`SlabAllocator` / `PooledDirectAllocator`)

The two platform pools are thin facades over a common chunk-based pool skeleton, `PooledAllocator`, which follows the jemalloc / Netty playbook:

- **Size-class ladder**: an `allocate` request is rounded **up** to the smallest size class that can hold it (16-byte quantum, 4 classes per doubling, ~20–25 % worst-case internal fragmentation) and served from that class's freelist — any requested size becomes poolable, not just pre-registered ones. The returned buffer's capacity is the class size, which satisfies the `allocate` contract of "at least `capacity` bytes".
- **Chunk arena**: a pool miss does not hit the platform heap per buffer. Buffers are carved as sub-ranges of large chunks held in a sharded arena (roughly one shard per EventLoop, each shard guarded by its own lock) shared by all children of one root allocator; freeing a carved buffer returns its run to the chunk.
- **Total-bytes budget**: `maxTotalBytes` (default 2 MiB) caps the worst-case bytes the freelists may retain. Slot counts are clamped at install time and freelists fill lazily, so real residency stays far below the cap.
- **Large allocations bypass the pool**: requests above the largest cached class (32 KiB) are allocated at exact size and freed on release, never pooled. (256 KiB is a different constant: the chunk size the arena carves pooled buffers from.)
- **`hintSizeClass(byteSize, maxCount)`** is a best-effort warm-cache hint, not a contract: duplicate hints for the same size are no-ops, and `maxCount` may be clamped downward to respect the budget.

The freelist concurrency strategy differs per platform. `SlabAllocator` (Native) uses a spin-lock `ArrayDeque` per size class — essentially free for the EL-pinned engines that access it uncontended — plus a confinement check on release: a release from a thread (or GCD queue) other than the owning one is routed back to the owner through a lock-free MPSC return queue instead of touching the freelist, keeping off-EventLoop consumers safe. `PooledDirectAllocator` (JVM) uses a `ReentrantLock`-guarded freelist per size class; an earlier lock-free stack design was ABA-unsafe under genuine concurrency (an off-EventLoop `asSource` refill racing the EventLoop's read path) and was replaced — the uncontended cost difference is a few nanoseconds per pool round-trip.

Treat the pool internals named here (chunk arena, shards, freelist types) as implementation detail rather than API: they are tuned and reshaped between releases.

Both pools implement the zero-copy operations:

- **`wrapBytes`**: a pinned `ByteArray` + `CPointer` view on Native (unpinned on release), a `ByteBuffer.wrap` view on JVM. The backing array is caller-owned and must not be mutated until the buffer is released.
- **`slice`**: zero-copy on both platforms; retains the source and installs a `SliceOwner` that releases it when the slice's count reaches zero.

### io_uring engine specifics

The io_uring engine's inbound read path does not use `BufferAllocator`. It surfaces kernel-managed `ProvidedBufferRing` slots as `RingBufferIoBuf`. Other read paths and all write paths use the configured allocator. On the send side, the engine can additionally pre-register buffer memory with the kernel — controlled by the public `RegisteredBufferStrategy` engine option (default `STATIC`: register a fixed set of per-EventLoop slots at startup) — so that a zero-copy send whose data lives in registered memory is dispatched as `SEND_ZC_FIXED` (skipping per-send page pinning), with automatic fallback to regular `SEND_ZC` otherwise.

## Per-platform implementation

The concrete `IoBuf` implementation differs per platform. The three keel-io implementations share a common skeleton (`AbstractIoBuf`) that owns the index pair, the **atomic refcount** and the owner dispatch; `writeByte` / `readByte` skip bounds checks to keep the hot path thin, and only bulk operations (array-based read/write, `copyTo`) perform bounds checks.

### Comparison of the four implementations

| Implementation | Target | Backing storage | `close()` behaviour |
|---|---|---|---|
| `DirectIoBuf` | JVM | `ByteBuffer.allocateDirect` or a carved view of a pooled chunk | Forces the refcount to 0; the direct buffer is left to the JVM GC |
| `NativeIoBuf` | Native | `nativeHeap.allocArray<ByteVar>` or a carved view of a pooled chunk | Forces the refcount to 0; owned heap memory is freed (idempotent) |
| `TypedArrayIoBuf` | JS | `Int8Array(capacity)` (V8 heap) | Forces the refcount to 0; the array is reclaimed by V8's GC |
| `RingBufferIoBuf` | io_uring engine | `ProvidedBufferRing` slot (kernel-managed) | Abandons the slot (`AutoCloseable` compat only; the normal path is `release()`) |

### `DirectIoBuf` (JVM)

- **Backing storage**: a standalone `ByteBuffer.allocateDirect(capacity)`, a view of a pooled chunk carved by `PooledDirectAllocator`, or an externally supplied `ByteBuffer` (wrap path). Capacity is fixed at construction.
- **Refcount / release path**: inherited from `AbstractIoBuf` — atomic CAS; at zero, `owner.release(this)` runs the backing strategy (pool return, slice-parent release, unpin, or GC no-op).
- **`close()` behaviour**: escape hatch — an atomic CAS forces the refcount to 0 and skips the owner; the direct buffer is left to the JVM GC.
- **`writeByte` / `readByte`**: `buf.put(writerIndex++, value)` / `buf.get(readerIndex++)`, no bounds check (hot-path thinning).
- **`writeByteArray` / `readByteArray`**: bounds-checked; use `ByteBuffer.put(src, offset, length)` / `get(dst)` for the bulk copy.
- **`clear`**: resets both indices and rewinds the backing `ByteBuffer`'s position/limit (a stale limit left by a previous `SocketChannel.write` would otherwise break absolute `put()`).
- **`copyTo`**: creates a duplicated view and uses `ByteBuffer.put` to transfer.
- **Engine accessor**: `unsafeBuffer: ByteBuffer`, gated behind the `@UnsafeIoBufApi` opt-in, used for zero-copy NIO syscalls.
- **`wrapExternal`**: companion factory that wraps a pre-allocated `ByteBuffer` (initializing `writerIndex = bytesWritten`) with an optional custom `IoBufOwner` — the JVM seam for external-resource wrapping.

### `NativeIoBuf` (Native: Linux / macOS)

- **Backing storage**: a standalone allocation via `nativeHeap.allocArray<ByteVar>(capacity)`, a carved view of a pooled chunk, or an external / slice view. An internal `ownsMemory` flag records whether the buffer owns its native allocation (views and wraps do not).
- **Refcount / release path**: inherited from `AbstractIoBuf` — atomic CAS; at zero, `owner.release(this)`. For an owned heap allocation, `HeapOwner` routes back to the buffer's free routine, which calls `nativeHeap.free` exactly once (a `freed` flag guards double-free); a chunk-carved view instead returns its run to the chunk. **Of the four implementations, this is the only one that performs an actual native memory deallocation.**
- **`close()` behaviour**: escape hatch — an atomic CAS forces the refcount to 0, then the owned backing is freed directly (skipping any custom owner). Idempotent.
- **`writeByte` / `readByte`**: `ptr[writerIndex++] = value` / `ptr[readerIndex++]`, no bounds check.
- **`writeByteArray` / `readByteArray`**: bounds-checked; pin + `memcpy(ptr + index, src, length)` for bulk copy.
- **`copyTo`**: when the destination implements `NativePointerAccess` (exposes `unsafePointer`), transfers directly with `memcpy`.
- **Engine accessor**: `unsafePointer: CPointer<ByteVar>`, gated behind the `@UnsafeIoBufApi` opt-in, used for POSIX syscalls (`read(2)` / `write(2)` / `writev(2)`).
- **External wrapping**: the public seam is the top-level `wrapExternalNativePtr(ptr, length, unpin)`, which wraps externally-owned native memory and invokes `unpin` once at refcount zero; allocator paths (pinned `wrapBytes`, slices) use the internal companion factory with an explicit owner.

### `TypedArrayIoBuf` (JS)

- **Backing storage**: `Int8Array(capacity)` allocated on the V8 heap; `capacity = array.length`.
- **Refcount / release path**: inherited from `AbstractIoBuf` — same atomic contract as the other platforms (JS is single-threaded, so it never contends); at zero, `owner.release(this)`.
- **`close()` behaviour**: escape hatch — forces the refcount to 0 and skips the owner; the `Int8Array` is reclaimed by V8's GC.
- **`writeByte` / `readByte`**: `base.asDynamic()[writerIndex++] = value` / `(base.asDynamic()[readerIndex++] as Int).toByte()`. The `asDynamic()` cast is required because Kotlin/JS IR mode does not compile typed-array indexing directly; the dynamic call lands on V8-native operations.
- **`writeByteArray` / `readByteArray`**: bounds-checked; element-wise loops via `asDynamic()`.
- **`copyTo`**: `destBuf.set(buf.subarray(readerIndex, ...), dest.writerIndex)`, V8's optimized typed-array bulk copy.
- **Engine accessor**: `unsafeArray: Int8Array` (property + extension), used for Node.js `net.Socket.write` and similar APIs.
- **`wrapExternal`**: internal companion factory that wraps a pre-allocated `Int8Array` and sets `writerIndex = bytesWritten`. The JS heap is GC-managed so the default owner is a no-op.

### `RingBufferIoBuf` (io_uring engine)

This implementation is qualitatively different from the other three. It is a **wrap-only class that never goes through an allocator** — each slot is pre-allocated in a `ProvidedBufferRing` at startup, and the `IoBuf` instance is reused across CQE callbacks. It is engine-direct: it implements `IoBuf` itself instead of extending `AbstractIoBuf`, and self-manages its backing.

- **Backing storage**: a slot in the `ProvidedBufferRing`. The pointer is computed via `bufferRing.getPointer(bufId)` and cached in the constructor (avoiding per-access recomputation).
- **Refcount**: a plain `Int` — safe here because the buffer never leaves its owning EventLoop.
- **Lifecycle**: no `allocate` factory. Slot-bound instances are created once at source startup, and each CQE callback calls `reset()` (which resets the indices and `refCount = 1` while preserving `ptr` and the `bufferRing` reference). **Hot-path object allocation is zero.**
- **Release path**: when `refCount` reaches 0, the slot is returned straight to the ring via `ProvidedBufferRing.returnBuffer(bufId)` — no `IoBufOwner` dispatch.
- **`close()` behaviour**: escape hatch — sets `refCount = 0` without returning the slot, so the slot is **intentionally abandoned** for `AutoCloseable` compatibility; the normal path is `release()`.
- **`writeByte` / `readByte` / bulk ops**: structurally identical to `NativeIoBuf` (`ptr[index++]` / `memcpy` / `memmove`).
- **Engine accessor**: `unsafePointer: CPointer<ByteVar>` (implements `NativePointerAccess`), used to submit SQEs (`io_uring_prep_recv`, etc.).
- **Platform-unique**: the provided-buffer-ring inbound path allocates nothing per CQE. (Registered "fixed" buffers on the send side are a separate, already-shipped mechanism — see the io_uring notes in the allocator section above.)

### Shared design policies

- **Atomic `refCount` for the keel-io implementations**: `AbstractIoBuf` gives `DirectIoBuf` / `NativeIoBuf` / `TypedArrayIoBuf` the thread-safe lifecycle described in "Thread safety contract". Engine-direct implementations confined to one EventLoop (such as `RingBufferIoBuf`) may keep a plain count.
- **No bounds check on single-byte read/write**: an intentional trade-off for hot-path throughput. Boundary correctness is the caller's responsibility.
- **Bulk operations are bounds-checked**: methods that take a `length` (`writeByteArray` / `readByteArray` / `copyTo`) fail early on invalid arguments.
- **Engine-specific accessors**: each implementation exposes its platform-native memory primitive under an `unsafe*` name, gated behind the `@UnsafeIoBufApi` opt-in on JVM / Native. These are not part of the `IoBuf` interface (they are inherently platform-specific).

## Large-payload optimization

This section describes a performance problem that arises when sending large responses via `keel-server-ktor` and the automatic mechanism that resolves it. Ordinary Ktor handlers need not think about this, but understanding the behaviour helps when interpreting benchmark results or designing large-file / streaming-body paths.

### Background: role of `BufferedSuspendSink`

The write path for a Ktor application response looks like:

1. The handler calls `call.respondBytes(byteArray)` or `call.respondOutputStream { ... }`.
2. Ktor's write path reaches the `keel-server-ktor` transport adapter.
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

keel expresses this via [`IoBufOwner`](https://github.com/fukusaka/keel/blob/main/keel-io/src/commonMain/kotlin/io/github/fukusaka/keel/buf/IoBufOwner.kt),
a plain interface installed on every keel-io buffer when the buffer is
created. `IoBuf.release()` invokes `owner.release(this)` exactly once
when the refcount reaches zero. (`IoBuf.close()` bypasses the owner —
that is precisely what makes it an escape hatch.)

### Strategy taxonomy

| Strategy | Backing | Extra state | Release behaviour |
|---|---|---|---|
| `HeapOwner` (singleton) | `nativeHeap` / `ByteBuffer.allocateDirect` / `Int8Array` | none | frees via the buffer's own free routine — `nativeHeap.free` on Native; no-op on GC-managed JVM/JS backings |
| `PoolOwner` | platform backing, managed by a pool | `returnToPool` lambda | return the buffer to its size-class freelist |
| `SliceOwner` | sub-range of a parent `IoBuf` | `parent` ref | `parent.release()` |
| `ExternalWrapOwner` | caller's pinned `ByteArray` or other external resource | `unpin` lambda | drop the pin / external hold |

These four implementations are `internal` to keel-io — they encode
keel-io-specific concepts (pool, slice, external wrap) and are not part
of the public API. The `IoBufOwner` interface itself is deliberately
**plain, not sealed**, and public: external code can implement a custom
strategy and pass it where the API accepts an `IoBufOwner` — currently
`DirectIoBuf.wrapExternal` on JVM; on Native, `wrapExternalNativePtr`
takes an `unpin` lambda and wraps it as an `ExternalWrapOwner`
internally.

Engine-direct `IoBuf` implementations sit outside the owner taxonomy
entirely: `RingBufferIoBuf` (engine-io-uring) returns its kernel-managed
slot straight to the `ProvidedBufferRing`, and `NettyByteBufIoBuf`
(engine-netty) delegates to the wrapped Netty `ByteBuf`'s own reference
count — both implement `IoBuf` directly and self-manage their backing
without an `IoBufOwner`.

io_uring's registered (fixed) buffers are likewise not an owner
strategy: the engine pre-registers buffer memory with the kernel per
the public `RegisteredBufferStrategy` engine option (default `STATIC`),
and the write path selects `SEND_ZC_FIXED` by looking the buffer's
pointer up in the per-EventLoop registry — falling back to the regular
zero-copy send when the lookup misses. No owner type is involved in
that dispatch.

### When this matters in practice

Ordinary callers never notice: `allocator.allocate(size)` returns an
`IoBuf` with the correct owner for the backing storage, and
`buf.release()` does the right thing. Engines do not query owners on
the common path either.

Where the taxonomy pays off:

- **Pool return path**: `PoolOwner` captures the single pool instance
  and is reused across all allocations from that pool — one shared
  owner per pool, so pool hits allocate no closure.
- **Slice safety**: `SliceOwner` stores only a retained parent ref, so
  a pooled parent returns to its freelist only after every outstanding
  slice view has been released — no view can outlive its backing.
- **Leak detection**: `TrackingAllocator` and `LeakDetectingAllocator`
  wrap the owner in place (via an internal seam on the keel-io buffer
  types) so every release goes through a counting or stack-recording
  decorator before reaching the real owner. Engine-direct buffers
  without that seam are covered by the separate
  `BufferAllocatorLifecycleListener` observability channel instead.

## Comparison with other buffer APIs

Most developers approaching keel already know a buffer API from another networking library. The table below compares `IoBuf` with six representative implementations.

| Feature | keel `IoBuf` | Netty `ByteBuf` | SwiftNIO `ByteBuffer` | tokio `bytes::Bytes`/`BytesMut` | NIO `ByteBuffer` | kotlinx.io `Buffer` |
|---|---|---|---|---|---|---|
| Reference counting | Yes (atomic) | Yes (atomic) | Swift CoW (value type) | Yes (`Arc`, atomic) | No (GC) | No (GC) |
| Ownership model | Transfer | Transfer | Value type + CoW | Move (Rust default) | N/A | N/A |
| Reader / writer index | Separate | Separate | Separate | Single cursor + `split_to` | Shared `position` / `limit` | Segmented |
| Off-heap memory | Yes (JVM/Native) | Yes (pooled direct) | N/A (Swift-managed) | N/A (Rust-managed) | Optional (`allocateDirect`) | No |
| Target platform | All KMP targets | JVM | Apple platforms (Swift) | All Rust targets | JVM | KMP |
| Zero-copy slice | `allocator.slice(...)` | `slice()` / `retainedSlice()` | `slice()` / `readSlice(n)` | `Bytes::slice` / `split_to(n)` | `slice()` | Segment reference |
| Compaction | None by design (fixed capacity; release + reallocate) | `discardReadBytes()` | `discardReadBytes()` | Implicit via `split` | `compact()` | Segment rebalance |

**Design families at a glance**:

- **keel `IoBuf` / Netty `ByteBuf` / SwiftNIO `ByteBuffer`**: dual-index model (separate reader/writer) + reference counting (or CoW). SwiftNIO adapted Netty's design to Swift's value semantics via CoW; keel adapts Netty's design to KMP — atomic lifecycle, single-threaded content access, fixed capacity.
- **tokio `bytes`**: a Rust-idiomatic split into `Bytes` (immutable, shared via `Arc`) and `BytesMut` (mutable, exclusive). The `split_to` / `split_off` operations correspond to Netty's slice + retain.
- **NIO `ByteBuffer`**: single-cursor (`position` / `limit`) + GC-managed. A low-level primitive whose `flip()` / `clear()` mental model is idiosyncratic.
- **kotlinx.io `Buffer`**: a linked list of segments, GC-managed. Complementary to `IoBuf` — keel typically uses `IoBuf` at the transport layer and `kotlinx.io Buffer` at the codec layer.

**Takeaways**:

- **Netty users**: the mental model is identical — both at the pipeline layer (handler-to-handler) and at the `Channel.write` boundary, ownership transfers to the callee. Call `buf.retain()` before the write if you need to keep a reference. The refcount is atomic like Netty's; the remaining difference is a fixed capacity (no dynamic resize).
- **SwiftNIO users**: the API shape is close. The difference is explicit `retain` / `release` instead of value type + CoW; forgetting `release()` leaks (SwiftNIO delegates refcounting to the language).
- **tokio `bytes` users**: `IoBuf` is closer to `BytesMut`, but relies on `retain()` for refcount sharing rather than `split` to create independent handles.
- **NIO users**: separate reader and writer indices remove the need for `flip()`. In return, `release()` is required at end-of-life.
- **kotlinx.io users**: `IoBuf` is lower-level (single buffer, no segment list) and offers predictable off-heap memory behaviour. They compose well: codec layer uses `kotlinx.io Buffer`, transport layer uses `IoBuf`.

Adjacent APIs not included in the table: .NET `Memory<byte>` / `IMemoryOwner<byte>` (pool-based, no refcount), Go `bytes.Buffer` (GC-managed, growable), libuv `uv_buf_t` (C struct: base + len view only). keel has no direct relationship with these, so they are omitted from the comparison.

## See also

- `IoBuf` KDoc: [`keel-io/.../buf/IoBuf.kt`](https://github.com/fukusaka/keel/blob/main/keel-io/src/commonMain/kotlin/io/github/fukusaka/keel/buf/IoBuf.kt)
- `BufferAllocator` KDoc: [`keel-io/.../buf/BufferAllocator.kt`](https://github.com/fukusaka/keel/blob/main/keel-io/src/commonMain/kotlin/io/github/fukusaka/keel/buf/BufferAllocator.kt)
- Netty reference counting: [Reference Counted Objects](https://netty.io/wiki/reference-counted-objects.html)
