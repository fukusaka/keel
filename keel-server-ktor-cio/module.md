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

## Status

MVP scaffolding — module skeleton + `KeelCio` factory only.  The connection handler
implementation (byte-channel pumps + `parseRequest` / `parseHttpBody` integration +
`KeelCioApplicationCall` / Request / Response triple) lands in a follow-up PR.
Calling `KtorCioConnectionHandler.handle` currently throws.

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

`KeelCio` (factory object) and `KtorCioConnectionHandler`.
