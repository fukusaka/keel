# Module keel-codec-http

HTTP/1.1 codec — parser, writer, and pipeline handlers (RFC 7230/7231).

Depends on `keel-io` (for `BufferedSuspendSource` / `BufferedSuspendSink`) and `keel-core`
(for `Pipeline` and `PipelinedChannel`).

## Two API Styles

**Synchronous** (blocking/`kotlinx.io.Source`): use these when reading from a `kotlinx.io.Source`
(e.g., tests, or engines that expose a blocking source):

```kotlin
val request: HttpRequest = parseRequest(source)
writeResponse(response, sink)
```

**Suspend** (`BufferedSuspendSource` / `BufferedSuspendSink`): use these for async I/O
with engine channels. Zero-copy: reads directly from `IoBuf` without a `kotlinx.io.Buffer` copy:

```kotlin
val source = channel.asBufferedSuspendSource()
val sink = BufferedSuspendSink(channel.asSuspendSink(), channel.allocator, channel.supportsDeferredFlush)

val head: HttpRequestHead = parseRequestHead(source)   // suspend
writeResponseHead(response, sink)                       // suspend
```

`parseRequestHead` parses only the request line and headers; the body remains
in `source` for streaming consumption. This avoids reading the full body into memory
when only the headers are needed.

## Pipeline Mode

For high-performance HTTP servers, use the pipeline handler chain:

```
pipeline.addLast("encoder", HttpResponseEncoder())   // outbound: HttpResponseHead/HttpBody/HttpBodyEnd → IoBuf
pipeline.addLast("decoder", HttpRequestDecoder())    // inbound:  IoBuf → HttpRequestHead/HttpBody/HttpBodyEnd
pipeline.addLast("routing", RoutingHandler(mapOf(
    "/hello" to { _ -> HttpResponse.ok("Hello, World!") },
)))
```

`HttpRequestDecoder` emits a streaming message sequence per request:
`HttpRequestHead` → `HttpBody` × N → `HttpBodyEnd`. All types implement the
`HttpMessage` sealed interface.

To receive the full request body as `HttpRequest(body: ByteArray?)`, insert
`HttpBodyAggregator` between decoder and handler:

```
pipeline.addLast("encoder", HttpResponseEncoder())
pipeline.addLast("decoder", HttpRequestDecoder())
pipeline.addLast("aggregator", HttpBodyAggregator())   // HttpRequestHead+Body+BodyEnd → HttpRequest
pipeline.addLast("handler", MyHandler())
```

`RoutingHandler` is a terminal inbound handler: it silently releases body
messages (`HttpBody` / `HttpBodyEnd`) and routes by `HttpRequestHead.path`.

## Key Types

| Type | Notes |
|------|-------|
| `HttpMessage` | Sealed interface — common supertype for all streaming pipeline messages |
| `HttpRequestHead` | `method`, `uri`, `version`, `headers`. Computed: `path`, `queryString`, `isKeepAlive` |
| `HttpResponseHead` | `status`, `version`, `headers` (no body) |
| `HttpBody` | Streaming body chunk wrapping an `IoBuf`. Receiver must call `content.release()` |
| `HttpBodyEnd` | Terminal body marker + optional trailer headers. `HttpBodyEnd.EMPTY` singleton |
| `HttpRequest` | Aggregated request (produced by `HttpBodyAggregator`): `method`, `uri`, `version`, `headers`, `body?` |
| `HttpResponse` | Complete response: `status`, `version`, `headers`, `body?`. Factory: `ok(body)`, `notFound()` |
| `HttpHeaders` | Case-insensitive header map. `HttpHeaders.EMPTY` singleton |
| `HttpMethod` | `GET`, `POST`, `PUT`, `DELETE`, `HEAD`, `OPTIONS`, `PATCH` |
| `HttpStatus` | Status code + reason phrase. Constants: `OK`, `NOT_FOUND`, `BAD_REQUEST`, etc. |
| `HttpVersion` | `HTTP_1_0`, `HTTP_1_1`. Use `.text` for wire format (`"HTTP/1.1"`) |
| `HttpHeaderName` | Typed header name constants (`CONTENT_TYPE`, `CONTENT_LENGTH`, `HOST`, etc.) |
| `HttpRequestDecoder` | Pipeline handler: `IoBuf` → `HttpRequestHead` / `HttpBody` / `HttpBodyEnd` |
| `HttpResponseEncoder` | Pipeline handler: `HttpResponseHead` / `HttpBody` / `HttpBodyEnd` → `IoBuf` |
| `HttpBodyAggregator` | Pipeline handler: `HttpRequestHead` + `HttpBody` + `HttpBodyEnd` → `HttpRequest` |
| `RoutingHandler` | Terminal inbound handler: routes by path, releases body messages |

## Error Handling

| Exception | When thrown |
|-----------|-------------|
| `HttpParseException` | Malformed request/response line or headers |
| `HttpEofException` | Unexpected EOF while reading request line or headers |

# Package io.github.fukusaka.keel.codec.http

HTTP/1.1 parser, writer, and pipeline handlers (RFC 7230/7231).
Sync API via `kotlinx.io.Source`/`Sink`; suspend API via `BufferedSuspendSource`/`BufferedSuspendSink`.
