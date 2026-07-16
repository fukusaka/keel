# Module keel-server-ktor

Ktor server engine adapter — bridges keel `StreamEngine` to Ktor's `ApplicationEngine`,
using keel's `:keel-codec-http` codec stack (`HttpRequestDecoder` / `HttpResponseEncoder`
/ `HttpBodyAggregator`) for the HTTP/1.1 wire format.

Combines the codec-agnostic skeleton from `:keel-server-ktor-base` (`KeelApplicationEngine`,
`Configuration`, accept loop, shutdown) with `KeelCodecConnectionHandler` (per-connection
HTTP handling using keel codec). Use `embeddedServer(Keel)` to run any Ktor application
on keel's native-speed I/O engines (kqueue, epoll, io_uring, NIO, Netty, NWConnection)
with a single dependency change.

For Ktor users who want Ktor's own `ktor-http-cio` HTTP parser instead, the sibling
module `:keel-server-ktor-cio` provides a `KeelCio` factory wired with
`KtorCioConnectionHandler`.

## Usage

```kotlin
embeddedServer(Keel, port = 8080) {
    routing {
        get("/") { call.respondText("Hello, World!") }
    }
}.start(wait = true)
```

HTTPS with PEM certificates (works on all KMP targets):

```kotlin
embeddedServer(Keel) {
    sslConnector(tlsConfig, JsseTlsCodecFactory()) {
        host = "0.0.0.0"
        port = 8443
    }
    // HTTP and HTTPS can coexist on different ports
    connector { port = 8080 }
}.start(wait = true)
```

## Engine Selection

`Configuration.engine` is required — set it explicitly to the
`StreamEngine` your application wants to drive the I/O loop with. The
adapter does not ship a platform default so it does not have to depend
on every keel engine module just to pick one at runtime; the caller
decides which engine module(s) to depend on.

| Engine | Module |
|--------|--------|
| `NioEngine` | `keel-engine-nio` |
| `NettyEngine` | `keel-engine-netty` |
| `KqueueEngine` | `keel-engine-kqueue` (macOS host only) |
| `EpollEngine` | `keel-engine-epoll` (Linux host only) |
| `IoUringEngine` | `keel-engine-io-uring` (Linux host only) |
| `NwEngine` | `keel-engine-nwconnection` (macOS host only) |

Example:

```kotlin
embeddedServer(Keel, configure = {
    engine = NioEngine()
    connector { port = 8080 }
}) {
    // application module
}
```

Leaving `engine` unset throws `IllegalStateException` from `start()`.

## HTTP Pipeline Codec

Each accepted connection installs the full pipeline HTTP codec:

```
HEAD ↔ [TlsHandler] ↔ HttpResponseEncoder ↔ HttpRequestDecoder
     ↔ HttpBodyAggregator ↔ SuspendMessageBridge<HttpRequest> ↔ TAIL
```

- **Inbound**: `HttpRequestDecoder` decodes raw `IoBuf` into streaming messages,
  `HttpBodyAggregator` reassembles them into `HttpRequest`, and
  `SuspendMessageBridge` delivers to the suspend connection loop.
- **Outbound**: `KeelApplicationResponse` emits `HttpResponseHead` / `HttpBody` /
  `HttpBodyEnd` through `pipeline.requestWrite()`, and `HttpResponseEncoder`
  serialises them into wire-format `IoBuf`.

## Dispatcher Model

The pipeline HTTP codec runs on the channel's EventLoop thread (push-mode).
The Ktor application pipeline runs on `Configuration.applicationDispatcher`:
when null (default) it collapses to the channel's `ioDispatcher` so the entire
request flows on the EventLoop with zero per-request cross-thread dispatch.

| Engine | `ioDispatcher` (EventLoop) |
|---|---|
| epoll / kqueue / io-uring | Per-channel EventLoop thread (single pthread) |
| NIO | `NioEventLoop` Selector thread |
| Netty | `io.netty.channel.EventLoop` (per-channel, wrapped by `NettyEventLoopDispatcher`) |
| NWConnection | Per-connection GCD serial dispatch queue (wrapped by `NwConnectionQueueDispatcher`) |
| Node.js | JS event loop (`Dispatchers.Unconfined`) |

User code that performs blocking I/O should wrap the blocking call in
`withContext(Dispatchers.IO)`. Alternatively, set
`applicationDispatcher = Dispatchers.Default` in the engine configuration to
offload the whole Ktor pipeline onto a work-stealing pool at the cost of one
hop per request.

## HTTP/1.1 Keep-Alive

`Configuration.keepAlive = true` (default): multiple requests are processed on a
single TCP connection. The connection closes when the client sends `Connection: close`
or an error occurs.

## TLS

`sslConnector(tlsConfig, installer)` adds an HTTPS connector. Two installer strategies:

- **`JsseTlsCodecFactory`** (all engines): keel `TlsHandler` is installed per connection in the keel pipeline. Works on all KMP targets.
- **`NettySslInstaller`** (JVM + Netty only): installs Netty's `SslHandler` at the Netty transport level. Decryption happens before data enters the keel pipeline.

HTTP and HTTPS connectors can coexist on different ports.

## Accept Backoff

`Configuration.acceptBackoff` controls retry behavior when `server.accept()` fails
(e.g. EMFILE — too many open files):

- `AcceptBackoff.Fixed(delayMs)`: constant delay (default: 100ms)
- `AcceptBackoff.Exponential(initialMs, maxMs)`: doubles on each failure, resets on success (default: 100ms–1s)

## Key Types

| Type | Role |
|------|------|
| `Keel` | `ApplicationEngineFactory` object — use with `embeddedServer(Keel)` |
| `KeelApplicationEngine` | `BaseApplicationEngine` implementation |
| `KeelApplicationEngine.Configuration` | Engine settings: `engine`, `keepAlive`, `acceptBackoff`, `sslConnector()` |
| `KtorLoggerAdapter` | Bridges Ktor's `Logger` to keel's `LoggerFactory` |

`ServerConnector` (the `(host, port, tls?)` descriptor for a listen
endpoint) lives in `:keel-server` so engine adapters and future
HTTP-family servers can share the type without either side owning it.

# Package io.github.fukusaka.keel.server.ktor

`Keel` (factory object), `KeelApplicationEngine`, `KeelApplicationEngine.Configuration`,
and Ktor integration types (`KeelApplicationRequest`, `KeelApplicationResponse`,
`KeelApplicationCall`, `KeelHeaders`).
