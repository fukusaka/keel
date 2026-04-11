# Module keel-ktor-engine

Ktor server engine adapter — bridges keel `StreamEngine` to Ktor's `ApplicationEngine`.

Use `embeddedServer(Keel)` to run any Ktor application on keel's native-speed I/O engines
(kqueue, epoll, io_uring, NIO, Netty, NWConnection, Node.js) with a single dependency change.

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

`Configuration.engine` specifies the `StreamEngine` to use. When null,
the platform default is chosen automatically:

| Platform | Default engine |
|----------|----------------|
| JVM | `NioEngine` |
| macOS | `KqueueEngine` |
| Linux | `EpollEngine` |
| Node.js | `NodeEngine` |

Explicit override: `engine = NettyEngine(IoEngineConfig())`.

## Dispatcher Model

Connection I/O (read/parse) and Ktor application pipeline processing run on
the channel's `coroutineDispatcher` (EventLoop thread for kqueue/epoll/NIO),
keeping all request handling on a single thread — the same model as Netty's
EventLoop. For engines without a dedicated EventLoop (Netty, NWConnection, Node.js),
both use `Dispatchers.Default`.

User code that performs blocking I/O should use `withContext(Dispatchers.IO)` to
avoid stalling the EventLoop.

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
| `ServerConnector` | `(host, port, tls?)` descriptor for a single listen endpoint |
| `KtorLoggerAdapter` | Bridges Ktor's `Logger` to keel's `LoggerFactory` |

# Package io.github.fukusaka.keel.ktor

`Keel` (factory object), `KeelApplicationEngine`, `KeelApplicationEngine.Configuration`,
`ServerConnector`, and Ktor integration types (`KeelApplicationRequest`, `KeelApplicationResponse`,
`KeelApplicationCall`, `KeelHeaders`).
