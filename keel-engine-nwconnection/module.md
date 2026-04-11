# Module keel-engine-nwconnection

macOS/Apple `StreamEngine` implementation using Apple Network.framework
(`NWListener` / `NWConnection`).

Provides both **Pipeline mode** (zero-suspend, callback-driven I/O) and
**Coroutine mode** (suspend-based interactive I/O for protocols like SMTP, Redis, Ktor).

## Two I/O Modes

**Pipeline mode** (`bindPipeline`): Network.framework delivers data via `keel_nw_read`
callbacks. Handlers process data synchronously on the dispatch queue — zero suspend overhead.
Used for high-performance HTTP servers.

**Coroutine mode** (`bind`/`connect`): app drives I/O via suspend
`read()`/`write()`/`flush()`. A `SuspendBridgeHandler` bridges pipeline
callbacks to suspend. Used for interactive protocols (SMTP, Redis) and Ktor.

**Pipeline direction**: inbound (read) flows HEAD → TAIL; outbound (write/flush)
flows TAIL → HEAD. `HeadHandler` connects the pipeline to `NwIoTransport`.

## Architecture

```
NwEngine (Network.framework)
  |
  +-- bind() ---------> NwServer (wraps nw_listener_t; Coroutine mode)
  |                       |
  |                       +-- accept() --> NwPipelinedChannel (wraps nw_connection_t)
  |
  +-- bindPipeline() --> NwPipelinedServer (Pipeline mode)
  |
  +-- connect() -------> NwPipelinedChannel (wraps nw_connection_t)
```

All C-level operations (connect/receive/send) are handled by custom C wrapper
functions (`keel_nw_*` in `nwconnection.def`) via `staticCFunction` + `StableRef`.
Suspension is implemented via `suspendCancellableCoroutine` resumed by dispatch
queue callbacks — no thread blocking occurs.

`bindPipeline` blocks on `dispatch_semaphore` until the `NWListener` reaches the
ready state (Pipeline zero-coroutine principle). `bind` suspends via coroutine.

Note: `BindConfig.backlog` is ignored. `NWListener` does not expose a configurable
listen backlog; the OS manages it internally.

## Read Path

Network.framework delivers data as `dispatch_data_t`. The C wrapper `keel_nw_read_async`
iterates over data segments via `dispatch_data_apply` and copies each segment into
`IoBuf` via `memcpy`. This copy is unavoidable — the same structural constraint as
Netty and Node.js.

**Pipeline**: `armRead()` is called immediately after pipeline setup.

```
keel_nw_read callback → IoBuf copy (dispatch_data bytes)
  → pipeline.notifyRead(buf)
    → handler chain (Decoder → Router → ...)
```

**Coroutine**: `armRead()` is called lazily when `ensureBridge()` is called.

```
keel_nw_read callback → IoBuf copy (dispatch_data bytes)
  → pipeline.notifyRead(buf)
    → SuspendBridgeHandler.onRead() → queue
                                        ↓
App:                    suspend channel.read(buf) ← dequeue
```

## Write/Flush Path

Both modes share the same outbound path. The pipeline terminates at `HeadHandler`,
which delegates to `NwIoTransport`.

**Pipeline**: a handler calls `ctx.propagateWrite/Flush` to push data toward HEAD.

```
handler (e.g. Encoder)
  → ctx.propagateWrite(buf) → HeadHandler.onWrite → NwIoTransport.write(buf)
  → ctx.propagateFlush()   → HeadHandler.onFlush → NwIoTransport.flush()
```

**Coroutine**: the app enters the pipeline at TAIL.

```
App: channel.write(buf)        → pipeline.requestWrite(buf) → ... → NwIoTransport.write(buf)
App: channel.requestFlush()    → pipeline.requestFlush()    → ... → NwIoTransport.flush()
```

Flush strategy: pending `IoBuf`s are submitted via `keel_nw_write_async` (single
buffer) or `keel_nw_writev_async` (gather write for multiple buffers). NWConnection
accepts data without EAGAIN — `nw_connection_send` enqueues data immediately and
delivers completion asynchronously via a dispatch queue callback. Buffer references
are released in the completion callback.

## C Interop Pattern

`NWConnection` C API requires C function pointers for callbacks. Kotlin closures
cannot be passed directly. The pattern used throughout:

```kotlin
val ref = StableRef.create(callbackContext)
keel_nw_start_conn_async(conn, queue, staticCFunction { result, ctx ->
    val ref = ctx!!.asStableRef<CallbackContext<Int>>()
    ref.get().tryResume(result)
    ref.dispose()
}, ref.asCPointer())
```

`CallbackContext` prevents double-resume when the state handler fires multiple
times (ready → cancelled) or after coroutine cancellation.

## TLS Integration

`NwEngine` supports listener-level TLS (`TlsConnectorConfig` with `installer = null`):
`NwTlsParams.createTlsParameters` builds `nw_parameters_t` with `SecIdentity`
created from the DER-encoded certificate and private key. Connections arrive already
decrypted — no keel `TlsHandler` is needed:

```
Network.framework:  TLS termination at nw_connection_t level
keel pipeline:      HEAD → ... → TAIL   (no TlsHandler)
```

PKCS#8-wrapped private keys are unwrapped by `Pkcs8KeyUnwrapper` before
`SecIdentity` creation.

## Key Classes

| Class | Role |
|-------|------|
| `NwEngine` | `StreamEngine` implementation for macOS/Apple |
| `NwPipelinedChannel` | Unified channel: Pipeline + Coroutine modes. Wraps `nw_connection_t` |
| `NwIoTransport` | `IoTransport` for write/flush via `keel_nw_write_async` / `keel_nw_writev_async` |
| `NwServer` | Coroutine-mode server: wraps `nw_listener_t`, queues connections for `accept()` |
| `NwTlsParams` | Builds `nw_parameters_t` with `SecIdentity` for listener-level TLS |
| `CallbackContext` | Thread-safe single-resume guard for C dispatch-queue callbacks |

# Package io.github.fukusaka.keel.engine.nwconnection

macOS Network.framework `StreamEngine` with `NWListener`/`NWConnection`, unified Pipeline + Coroutine
mode via `NwPipelinedChannel`, and listener-level TLS via `SecIdentity`/`nw_parameters_t`.
