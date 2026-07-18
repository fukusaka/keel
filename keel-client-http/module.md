# Module keel-client-http

Native HTTP/1.1 client built directly on a keel `StreamEngine` — no external
framework. `KeelHttpClient` opens an outbound connection with
`StreamEngine.connect`, installs the `:keel-codec-http` client codec on the
connection's pipeline, writes a typed `HttpRequest`, and returns the aggregated
`HttpResponse` as a plain, GC-owned `KeelHttpResponse`. It is the client-side
counterpart of `:keel-server-http`.

## Scope: fresh connect

This first layer opens a fresh connection per request and closes it afterwards —
no connection pool, no keep-alive reuse. That is the honest baseline for
connection-setup cost and the floor a pooled layer improves on. `http://` only;
`https://` is rejected until the native-stack client TLS prerequisite lands.
Keep-alive + connection pool, redirect following, and cookie / auth / cache
plugins are later additions.

## Usage

Construct via the `keelHttpClient { }` DSL. The `StreamEngine` is owned by the
caller and is never closed by the client:

```kotlin
val engine = NioEngine()                       // any keel StreamEngine
val client = keelHttpClient(engine)

val res = client.get("http://127.0.0.1:8080/hello")
println(res.status)                            // HttpStatus(200)
println(res.bodyText())                        // decoded body

val posted = client.post(
    "http://127.0.0.1:8080/echo",
    body = "ping".encodeToByteArray(),
)
```

Each call opens a connection, drives one request/response through
`addHttp1ClientCodec` (`HttpRequestEncoder` / `HttpResponseDecoder` /
`HttpResponseBodyAggregator`), materialises the decoder's zero-copy pooled
headers into a GC-owned `HttpHeaders`, releases the pooled buffers, and closes
the connection. There is no built-in timeout — bound a call with `withTimeout`
so a hung peer cannot suspend the caller indefinitely.

# Package io.github.fukusaka.keel.client.http

The client runtime and its result type: `KeelHttpClient`, `KeelHttpResponse`,
and the `keelHttpClient(engine)` builder DSL.
