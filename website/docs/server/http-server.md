---
sidebar_position: 1
---

# HTTP Server DSL

`keel-server-http` is a native HTTP/1.1 server you configure with a single
Kotlin DSL block. It runs on every keel engine target — Linux (epoll,
io_uring), macOS (kqueue), JVM (NIO, Netty), and JS (Node.js) — from the
same source.

This page is a guided tour of the `keelHttpServer { }` DSL. Each section
adds one feature; by the end you have seen every builder method.

## Hello, world

```kotlin
import io.github.fukusaka.keel.engine.nio.NioEngine
import io.github.fukusaka.keel.server.http.keelHttpServer

fun main() {
    val engine = NioEngine()
    val server = keelHttpServer(engine) {
        connector { port = 8080 }
        get("/hello") { call -> call.respondText("Hello, world!") }
    }
    server.start()
}
```

`keelHttpServer(engine) { ... }` builds a server; `start()` binds the
socket and begins accepting connections. Any keel engine works in place of
`NioEngine` — `EpollEngine`, `KqueueEngine`, `IoUringEngine`, `NettyEngine`,
`NodeEngine`.

## The connector — where the server listens

`connector { }` configures the listening endpoint. Omit it and the server
binds an OS-assigned port on all interfaces.

```kotlin
connector {
    host = "0.0.0.0"   // bind address (an IP literal)
    port = 8080
    backlog = 128      // accept queue depth
}
```

To serve HTTPS, add a `tls { }` block:

```kotlin
connector {
    port = 8443
    tls {
        config = myTlsConfig                       // certificate + key
        strategy = ServerTlsStrategy.EngineNative   // required — no default
    }
}
```

`strategy` selects who performs the TLS handshake (the engine's native TLS,
or the keel TLS codec). It has no default — you choose explicitly.

## Routing

Register a handler for an HTTP method and path:

```kotlin
get("/users") { call -> call.respondText("all users") }
post("/users") { call -> /* create a user */ }
```

`get` / `post` / `put` / `delete` / `patch` / `head` / `options` are
shorthands; `route(method, path, handler)` takes the method as an argument.

### Path patterns

A path is matched segment by segment:

| Pattern | Matches | Example |
|---|---|---|
| `users` | exactly that segment | `/users` |
| `:id` | any one segment, captured as `id` | `/users/42` → `id = "42"` |
| `:id(int)` | one segment that is an integer | `/items/42` ✓ `/items/abc` ✗ |
| `:id(uuid)` | one segment that is a UUID | `/items/550e8400-...` |
| `:id(^[a-f]+$)` | one segment matching the regex | a custom pattern |
| `:id?` | the final segment, optionally | `/users` **and** `/users/42` |
| `*` | the rest of the path (final segment only) | `/static/css/site.css` → `"*" = "css/site.css"` |

```kotlin
get("/users/:id(int)") { call ->
    val id = call.pathParameters["id"]   // guaranteed numeric
    call.respondText("user $id")
}
```

A constrained parameter and a plain one can coexist — `/items/:id(int)` and
`/items/:id(uuid)` route `/items/42` and `/items/<uuid>` to different
handlers. A request to a registered path with an **unregistered method**
gets `405 Method Not Allowed` with an `Allow` header.

### Predicate routing

Two handlers can share one method and path and be chosen by a request
property — useful for content negotiation:

```kotlin
get("/report", accept("application/json")) { call -> /* JSON */ }
get("/report", accept("text/html"))        { call -> /* HTML */ }
```

Built-in predicates: `header(name, value)`, `query(name, value)`,
`accept(contentType)`, `host(name)`. The first registered handler whose
predicate accepts the request wins; a handler with no predicate is the
catch-all.

## Reading the request, writing the response

The handler receives an `HttpCall`:

```kotlin
post("/echo") { call ->
    call.method            // POST
    call.path              // "/echo"
    call.queryString       // "?a=1" → "a=1", or null
    call.headers["X-Foo"]  // a request header
    call.pathParameters["id"]

    val body: ByteArray = call.receiveBytes()   // read the whole body
    call.respondText(body.decodeToString())
}
```

Responses:

```kotlin
call.respondText("hi")                                 // 200 text/plain
call.respond(HttpResponse.of(HttpStatus.CREATED, "")) // any status + body
call.respondStream(head) { sink -> sink.write(buf) }   // chunked streaming
```

For large or streamed bodies, `receiveChunk()` returns one buffer at a time
instead of aggregating, and `respondStream` writes the response
incrementally.

## Middleware

`install` adds a stage that wraps **every** request. Middleware runs before
and after the handler and may short-circuit:

```kotlin
install { call, next ->
    val start = TimeSource.Monotonic.markNow()
    next()                                  // run the rest of the chain
    println("${call.method} ${call.path} — ${start.elapsedNow()}")
}
```

Call `next()` exactly once to continue, or skip it to short-circuit (an
auth check that responds `401` itself). Middleware runs in installation
order — the first installed is the outermost.

## Route groups

`route(prefix) { }` groups routes under a shared path prefix and
group-scoped middleware. Groups nest:

```kotlin
route("/api/v1") {
    install { call, next -> /* auth for everything under /api/v1 */ next() }

    get("/users") { call -> /* GET /api/v1/users */ }

    route("/admin") {
        install { call, next -> /* an extra check under /api/v1/admin */ next() }
        get("/stats") { call -> /* GET /api/v1/admin/stats */ }
    }
}
```

A group's `install` middleware applies only to that group's routes (and
its nested groups) — unlike the server-wide `install`, which wraps every
request. A nested group inherits the enclosing group's prefix and
middleware.

## Static files

Serve a directory:

```kotlin
staticFiles("/assets", "./public")   // GET /assets/css/site.css → ./public/css/site.css
```

`staticFiles` handles `Content-Type`, conditional GET (`ETag` /
`Last-Modified` → `304`), HTTP `Range` requests (`206 Partial Content`),
and has a five-layer path-traversal defense. `staticFile(urlPath, file)`
serves one file; `staticAssets(urlPath, source)` serves a custom asset
source.

## Compression

`compression { }` adds gzip / deflate response compression (negotiated
against the request `Accept-Encoding`) and, optionally, inbound
request-body decompression (`Content-Encoding`):

```kotlin
compression {
    encoder(GzipCodec, priority = 1)       // register codecs; higher priority wins q-value ties
    encoder(DeflateCodec, priority = 0)

    level = 6                              // compression level for every encoder (-1 = backend default)
    deflate {                              // DEFLATE-family tuning (gzip + deflate)
        windowBits = 15                    // LZ77 window, 8..15
        strategy = Strategy.HuffmanOnly    // strategy hint (advisory)
    }

    responseCondition {
        minContentLength = 1024            // skip tiny responses
        excludeContentTypePrefix("image/", "video/")   // already-compressed types (defaults cover these)
    }

    requestDecompression {                 // optional: decode Content-Encoding request bodies
        limit = 10L * 1024 * 1024          // max decoded size (zip-bomb guard)
        ratioLimit = 100                   // max output:input ratio
    }
}
```

`level` is format-independent; `deflate { }` carries the DEFLATE-specific
`windowBits` / `strategy`. The tuning is global to the DEFLATE-family
encoders (a future zstd codec would carry its own tuning). Without a
`requestDecompression { }` block, request bodies with a `Content-Encoding`
header pass through untouched.

## WebSocket

`webSockets { }` registers WebSocket endpoints. Inside it, each
`webSocket(path) { }` runs against an open `WsSession`:

```kotlin
webSockets {
    webSocket("/echo") {
        for (message in incoming) {   // incoming: a channel of whole messages
            send(message)             // echo it back
        }
    }
    webSocket("/chat/:room") {
        val room = pathParameters["room"]
        // ...
    }
}
```

`incoming` delivers whole `WsMessage`s (`Text` / `Binary`) — fragmented
frames are reassembled for you. `send` accepts a `WsMessage`, a `String`,
or a `ByteArray`.

To enable `permessage-deflate` compression, pass a codec and tune it:

```kotlin
webSockets(DeflateCodec) {
    deflate {
        contextTakeover = false             // RFC 7692; default false (bounds per-connection memory)
        threshold = 1024                     // messages smaller than this are sent uncompressed
        level = 6                            // DEFLATE level: -1 = backend default, 0..9
        strategy = Strategy.HuffmanOnly      // DEFLATE strategy hint (advisory)
    }
    webSocket("/chat") { for (m in incoming) send(m) }
}
```

`windowBits` is not a `deflate { }` knob for WebSocket — the LZ77 window is
set by the `server_max_window_bits` the handshake negotiates, not the server
configuration.

A `webSockets { }` block can also sit inside a `route(prefix) { }` group —
the WebSocket endpoints then inherit the group's prefix and middleware
(auth runs before the handshake):

```kotlin
route("/api/v1") {
    install { call, next -> /* auth */ next() }
    webSockets {
        webSocket("/chat") { for (m in incoming) send(m) }   // /api/v1/chat
    }
}
```

## Error handling

Replace the built-in `404` and `500`:

```kotlin
notFound { call ->
    call.respondText("nothing here", HttpStatus.NOT_FOUND)
}

exception<UserNotFoundException> { call, cause ->
    call.respondText("user ${cause.id} not found", HttpStatus.NOT_FOUND)
}
```

`notFound { }` runs when no route matches. `exception<T> { }` turns a
thrown exception of type `T` — escaping the handler or the middleware
chain — into a response, replacing the built-in `500` for that type.
Exception mappers are consulted in registration order, so register more
specific exception types first; an unmatched exception falls back to
`500`.

## Lifecycle

```kotlin
server.start()                                          // bind + accept
server.stop(gracePeriodMillis = 5_000, timeoutMillis = 10_000)
```

`stop` shuts down gracefully: idle keep-alive connections close at once,
in-flight requests finish with a `Connection: close` response, and any
survivors past the timeout are force-closed.

## DSL reference — what goes where

Most builder methods are available both at the top level and inside a
`route(prefix) { }` group. A few are top-level only:

| Method | Top level | In a group | Note |
|---|:--:|:--:|---|
| `connector { }` | ✓ | — | The listening socket is server-wide; a group is a path subtree of one socket. |
| `get` / `post` / … / `route` | ✓ | ✓ | |
| `route(prefix) { }` | ✓ | ✓ | Nestable. |
| `install` | ✓ | ✓ | Top level wraps every request; in a group, only the group's routes. |
| `webSockets { }` | ✓ | ✓ | |
| `staticFiles` / `staticFile` / `staticAssets` | ✓ | — | Group support is planned. |
| `notFound { }` | ✓ | — | "No route matched" is a server-wide event. |
| `exception<T> { }` | ✓ | — | Server-wide policy. For a group, use an `install` middleware with `try { next() } catch`. |

`connector` is the only method that is *inherently* server-wide — the
others are top-level by design choice, and a group equivalent is either
available (`install` for exception handling) or planned (`staticFiles`).
