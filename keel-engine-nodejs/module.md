# Module keel-engine-nodejs

JS (Node.js) `StreamEngine` implementation using Node.js's `net` module.

Provides both **Pipeline mode** (zero-suspend, callback-driven I/O) and
**Coroutine mode** (suspend-based interactive I/O for protocols like SMTP, Redis, Ktor).

## Two I/O Modes

**Pipeline mode** (`bindPipeline`): Node.js delivers data via `socket.on("data")` callbacks.
Handlers process data synchronously on the Node.js event loop — zero suspend overhead.
Used for high-performance HTTP servers.

**Coroutine mode** (`bind`/`connect`): app drives I/O via suspend
`read()`/`write()`/`flush()`. A `SuspendBridgeHandler` bridges pipeline
callbacks to suspend. Used for interactive protocols (SMTP, Redis) and Ktor.

**Pipeline direction**: inbound (read) flows HEAD → TAIL; outbound (write/flush)
flows TAIL → HEAD. `HeadHandler` connects the pipeline to `NodeIoTransport`.

## Architecture

```
NodeEngine (Node.js net module)
  |
  +-- bind() ---------> NodeServer (Coroutine mode: accept → suspend I/O)
  |                       |
  |                       +-- accept() --> NodePipelinedChannel
  |
  +-- bindPipeline() --> NodePipelinedServer (Pipeline mode: push I/O)
  |
  +-- connect() -------> NodePipelinedChannel
```

JS is single-threaded (Node.js event loop). All state fields are accessed from
the same thread — no locking or `@Volatile` needed.

All operations are callback-based internally, bridged to Kotlin coroutines via
`suspendCoroutine`.

Note: `port = 0` (ephemeral port) is not supported in `bindPipeline` because
Node.js assigns the port asynchronously in the listen callback.

## Read Path

Node.js delivers data via `socket.on("data")` before the user provides a buffer.
An `Int8Array` view is created over the incoming `Buffer`'s `ArrayBuffer`, then
`IoBuf.unsafeArray.set(srcView)` copies the data in a single native memcpy.
This copy is unavoidable — the same structural constraint as Netty and NWConnection.

**Pipeline**: `armRead()` is called immediately after pipeline setup.

```
socket.on("data", buffer) → IoBuf bulk copy (Int8Array)
  → pipeline.notifyRead(buf)
    → handler chain (Decoder → Router → ...)
```

**Coroutine**: `armRead()` is called lazily when `ensureBridge()` is called.

```
socket.on("data", buffer) → IoBuf bulk copy (Int8Array)
  → pipeline.notifyRead(buf)
    → SuspendBridgeHandler.onRead() → queue
                                        ↓
App:                    suspend channel.read(buf) ← dequeue
```

## Write/Flush Path

Both modes share the same outbound path. The pipeline terminates at `HeadHandler`,
which delegates to `NodeIoTransport`.

**Pipeline**: a handler calls `ctx.propagateWrite/Flush` to push data toward HEAD.

```
handler (e.g. Encoder)
  → ctx.propagateWrite(buf) → HeadHandler.onWrite → NodeIoTransport.write(buf)
  → ctx.propagateFlush()   → HeadHandler.onFlush → NodeIoTransport.flush()
```

**Coroutine**: the app enters the pipeline at TAIL.

```
App: channel.write(buf)        → pipeline.requestWrite(buf) → ... → NodeIoTransport.write(buf)
App: channel.requestFlush()    → pipeline.requestFlush()    → ... → NodeIoTransport.flush()
```

Flush strategy: each pending `IoBuf` is copied to a Node.js `Buffer` via
`Int8Array.subarray` + `Buffer.from` and sent via `socket.write()`. `flush()`
always returns `true` — `socket.write()` buffers data internally; no EAGAIN
handling is needed. Write backpressure is tracked via keel's own high/low
water marks on pending bytes, independent of Node.js flow control.

## TLS Integration

`NodeEngine` supports two TLS modes:

**Listener-level TLS** (`TlsConnectorConfig` with `installer = null`): creates a
`tls.Server` via `tls.createServer()`. TLS is handled by Node.js at the transport
level. The `"secureConnection"` event fires after the TLS handshake instead of
`"connection"`. No keel `TlsHandler` is needed in the pipeline.

**Per-connection TLS** (`TlsConnectorConfig` with non-null `installer`): creates a
plain `net.Server` and installs a keel `TlsHandler` per connection via
`BindConfig.initializeConnection`.

## Key Classes

| Class | Role |
|-------|------|
| `NodeEngine` | `StreamEngine` implementation for JS/Node.js |
| `NodePipelinedChannel` | Unified channel: Pipeline + Coroutine modes. Bridges `socket.on("data")` to keel pipeline |
| `NodeIoTransport` | `IoTransport` for write/flush via `socket.write()` |
| `NodeServer` | Coroutine-mode server: accepts connections into a suspend queue |
| `Net` | `@JsModule("net")` external declarations |
| `Tls` | `@JsModule("tls")` external declarations for listener-level TLS |

# Package io.github.fukusaka.keel.engine.nodejs

JS Node.js `StreamEngine` using `net`/`tls` modules, unified Pipeline + Coroutine
mode via `NodePipelinedChannel`, and listener-level or per-connection TLS.
