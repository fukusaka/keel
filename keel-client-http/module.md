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

## Custom clients at the pipeline layer

`KeelHttpClient` is a deliberately thin convenience. Anything richer — a
wire-level handler that observes or rewrites the raw exchange (the equivalent of
an OkHttp *network* interceptor) — is assembled directly on the connection's
pipeline, using public primitives, without a bespoke interceptor API:

- `StreamEngine.connectPipeline(host, port) { channel -> ... }` connects and runs
  the initializer on the channel's EventLoop thread (the client counterpart of
  `bindPipeline`).
- `PipelinedChannel.addHttp1ClientCodec()` installs the codec; its stages are
  named by the `Http1ClientCodec` constants, so a custom handler is positioned
  with `addBefore` / `addAfter` instead of hardcoded strings.
- `suspendMessageBridge<HttpResponse>()` bridges the pipeline back to a suspend
  call; the `releaseUndelivered` hook releases pooled headers on teardown.

```kotlin
// A wire-level handler runs on the EventLoop thread and MUST NOT block or
// suspend (like a Netty ChannelHandler). It sees raw inbound IoBuf here,
// before the decoder, then propagates it on.
class WireLog : InboundHandler {
    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        // observe msg ...
        ctx.propagateRead(msg)
    }
}

val bridge = suspendMessageBridge<HttpResponse>(
    releaseUndelivered = { it.headers.release() },
)
val channel = engine.connectPipeline("127.0.0.1", 8080) {
    it.addHttp1ClientCodec()
    it.pipeline.addBefore(Http1ClientCodec.DECODER, "wire-log", WireLog())
    it.pipeline.addLast("bridge", bridge)
    it.readEnabled = true
}
try {
    channel.pipeline.requestWriteAndFlush(HttpRequest(HttpMethod.GET, "/hello"))
    val response = bridge.receiveCatching().getOrThrow()
    // The delivered response owns the decoder's pooled headers; materialise
    // them to a GC-owned copy and release the pooled ones (as KeelHttpClient
    // does). `response.status` / `response.body` are already GC-owned.
    response.materializeReleasingHeaders { headers ->
        println(response.status)
        println(headers[HttpHeaderName.CONTENT_TYPE])
    }
} finally {
    channel.close()
}
```

Because handlers cannot suspend, call-level concerns that span retries or
redirects (an OkHttp *application* interceptor, a cookie jar, auth) do not
belong here — layer those above the client, or reach for a Ktor `HttpClientEngine`
adapter.

# Package io.github.fukusaka.keel.client.http

The client runtime and its result type: `KeelHttpClient`, `KeelHttpResponse`,
and the `keelHttpClient(engine)` builder DSL.
