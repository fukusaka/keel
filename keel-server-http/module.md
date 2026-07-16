# Module keel-server-http

Native HTTP/1.1 server built directly on a keel `StreamEngine` — no external
framework. `KeelHttpServer` binds a listening socket in Pipeline mode and
installs the `:keel-codec-http` server codec plus a request-dispatch stage on
every accepted connection, so the whole request lives on the connection's
EventLoop thread.

## Usage

Construct via the `keelHttpServer { }` DSL (`KeelHttpServerBuilder`):

```kotlin
val server = keelHttpServer(engine) {
    connector { host = "0.0.0.0"; port = 8080 }
    install { call, next -> next() }                    // middleware
    get("/hello") { call -> call.respondText("Hello") }
    get("/users/:id") { call -> call.respondText("user ${call.pathParameters["id"]}") }
}
server.start()
// ...
server.stop()
```

`start()` binds all declared connectors; `stop()` shuts down gracefully in
three phases (stop accepting → drain in-flight requests within a grace
period → cancel and force-close stragglers). The `StreamEngine` is owned by
the caller and is never closed by `stop()`. A stopped server can be started
again.

## Connectors

Each `connector { }` block (`HttpConnectorBuilder`) declares one listen
endpoint: `host` / `port` / `backlog` / `socketOptions`, plus `tls { }` for
HTTPS (delegating to `:keel-server`'s `ServerTlsBuilder`). Several blocks
may be declared — one application reachable through several doors. The block
also hosts the DoS-guard knobs:

| Knob | Guard |
|------|-------|
| `queryParameters { }` | query-string parse limits (`QueryParameterConfig` — parameter-count cap, strict modes) |
| `headerLimits { }` | per-request header limits (`HttpHeaderLimitsConfigBuilder`) |
| `headerTimeoutMillis` | header-complete deadline (slowloris defense) |
| `requestTimeoutMillis` | absolute request-total deadline |
| `minBodyRateBytesPerSec` | minimum sustained body throughput floor |

## Routing

`Router` holds routes in a segment trie. Pattern syntax: literal segments,
`:name` path parameters, constrained parameters (`:id(int)`, `:id(uuid)`, or
a regex), an optional trailing `:id?`, and a trailing `*` wildcard. Match
precedence is literal > parameter > wildcard, with backtracking.

- **Method shorthands** — `get` / `post` / `put` / `delete` / `patch` /
  `head` / `options`, or the general `route(method, path, ...)`.
- **Predicates** — a `RoutePredicate` (`header(...)`, `query(...)`,
  `accept(...)`, `host(...)`) guards a handler; several predicated handlers
  may share one method × path.
- **Content negotiation** — a handler may declare the media types it
  `produces`; `resolve` scores candidates against the request `Accept`
  header (RFC 9110 q-values) and answers `406 Not Acceptable` when nothing
  fits. A method mismatch on a registered path answers `405` with `Allow`.
- **Route groups** — `route(prefix) { }` opens a `RouteGroupBuilder`: routes
  registered inside are prefixed and wrapped with the group's `install`ed
  middleware; groups nest.

## Handling requests: HttpCall

Each `RouteHandler` receives an `HttpCall` and responds imperatively:

- **Request** — `method`, `uri`, `path`, `queryString`, `queryParameters`,
  `headers`, `pathParameters`.
- **Body** — `receiveChunk()` (zero-copy `IoBuf` streaming, caller
  releases), `receiveChunks()` (whole body as pooled `IoBufChunks`), or
  `receiveBytes()` (copying `ByteArray` convenience).
- **Response** — `respond(HttpResponse)`, `respondText(text, status)`, or
  `respondStream(head) { sink -> ... }` for chunked / SSE / large payloads.
  The `HttpResponseBodySink` takes ownership of each written `IoBuf`, and
  its `trailers` property emits trailer fields after the terminal chunk of a
  `Transfer-Encoding: chunked` response (RFC 7230 §4.1.2).

## Middleware and pipeline installers

`Middleware` (installed with `install`) wraps the per-call dispatch of every
request — including unmatched ones — running in registration order, and may
short-circuit (auth, logging, CORS). `PipelineInstaller` (registered with
`installPipeline`) is the wire-level counterpart: it adds handlers to each
connection's pipeline between the HTTP codec and the terminal dispatch
stage. The built-in `compression { }` DSL is implemented on this hook.

## Compression

`compression { }` (`CompressionBuilder`) configures outbound
`Content-Encoding` and optional inbound request decompression:
`encoder(codec, priority)` registers backends (e.g. `GzipCodec` from
`keel-compression-zlib`), `responseCondition { }` tunes when to compress,
`requestDecompression { }` enables inbound decoding, and `deflate { }`
passes backend tuning.

## Static files

`staticFiles(urlPath, directory) { }` serves a directory tree via
`FilesystemAssetSource`, which applies a 5-layer path-traversal defense
(single percent-decode, NUL rejection, lexical normalization, symlink
containment, regular-file check). `staticFile` serves one file at an exact
route; `staticAssets` mounts any custom `AssetSource`. Serving supports
conditional GET (`ETag` via a pluggable `ETagGenerator`, `Last-Modified`),
single- and multi-range `Range: bytes=` requests (`206 Partial Content`,
`multipart/byteranges`, `If-Range`), and `Content-Type` resolution via
`ContentTypeResolver` — both configurable per mount with
`StaticFilesBuilder`.

## Protocol upgrades

`upgrade(path, protocol)` registers an `UpgradeProtocol` — a hook that takes
over the raw `PipelinedChannel` when a request's `Upgrade` header names the
protocol. The WebSocket implementation lives in `keel-server-websocket`
(`WebSocketUpgrade` and the `webSockets { }` DSL build on this hook).

## Error handling

- `notFound { }` replaces the built-in `404` terminal.
- `exception<T> { call, cause -> ... }` maps a thrown exception type to a
  response; mappers are consulted in registration order, falling back to a
  built-in `500`.
- `dosLimitResponses()` registers the canonical RFC-aligned mappers for the
  codec's DoS-limit exceptions (`414 URI Too Long`, `431 Request Header
  Fields Too Large`).

# Package io.github.fukusaka.keel.server.http

Server runtime and routing: `KeelHttpServer`, `HttpCall`,
`HttpResponseBodySink`, `Router`, `RoutePredicate`, `Middleware`,
`UpgradeProtocol`, `PipelineInstaller`, and the static-asset types
(`AssetSource`, `Asset`, `FilesystemAssetSource`, `ContentTypeResolver`,
`ETagGenerator`).

# Package io.github.fukusaka.keel.server.http.dsl

The `keelHttpServer { }` builder DSL: `KeelHttpServerBuilder`,
`HttpConnectorBuilder`, `RouteGroupBuilder`, `StaticFilesBuilder`,
`CompressionBuilder`, `HttpHeaderLimitsConfigBuilder`,
`QueryParameterConfigBuilder`, and `dosLimitResponses()`.
