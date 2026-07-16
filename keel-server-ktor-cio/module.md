# Module keel-server-ktor-cio

Ktor server engine adapter — bridges keel `StreamEngine` to Ktor's `ApplicationEngine`,
using [`ktor-http-cio`'s][io.ktor.http.cio] HTTP parser (`parseRequest` / `parseHttpBody`)
in place of keel's own `:keel-codec-http` codec stack.

Combines the codec-agnostic skeleton from `:keel-server-ktor-base` (`KeelApplicationEngine`,
accept loop, shutdown) with `KtorCioConnectionHandler` (per-connection HTTP handling using
`ktor-http-cio`'s parser).  Use `embeddedServer(KeelCio)` to run any Ktor application on
keel's I/O engines but with Ktor's own HTTP wire-format implementation.

For Ktor users wanting keel's `:keel-codec-http` codec, the sibling module `:keel-server-ktor`
provides the `Keel` factory wired with `KeelCodecConnectionHandler`.

## Implementation

`KtorCioConnectionHandler.handle` installs `KtorCioInboundBridge` as the terminal
pipeline handler (inbound `IoBuf` → coroutine channel with high/low-watermark
backpressure), launches the byte-channel pumps on the engine scope, and drives the
per-request loop with `parseRequest` / `parseHttpBody`, dispatching each request
through the `KeelCioApplicationCall` / `KeelCioApplicationRequest` /
`KeelCioApplicationResponse` triple. Streaming response bodies are written through
`CioKeelStreamChannel`, which emits HTTP/1.1 chunked framing straight to the
transport. On Kotlin/Native, header parsing and header release are serialised
through `HeaderParseMutex` to avoid lock contention in ktor-http-cio's shared
header pool (no-op on JVM).

## Usage

```kotlin
embeddedServer(KeelCio, port = 8080) {
    engine = NioEngine()
    routing {
        get("/") { call.respondText("Hello, World!") }
    }
}.start(wait = true)
```

# Package io.github.fukusaka.keel.server.ktor.cio

`KeelCio` (engine factory object). The connection handler and the
call/request/response types are internal implementation details.
