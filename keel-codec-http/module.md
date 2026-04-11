# Module keel-codec-http

HTTP/1.1 codec — parser, writer, and pipeline handlers (RFC 7230/7231).

Depends on `keel-io` (for `BufferedSuspendSource` / `BufferedSuspendSink`) and `keel-core`
(for `ChannelPipeline` and `PipelinedChannel`).

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
pipeline.addLast("encoder", HttpResponseEncoder())   // outbound: HttpResponse → IoBuf
pipeline.addLast("decoder", HttpRequestDecoder())    // inbound:  IoBuf → HttpRequestHead
pipeline.addLast("routing", RoutingHandler(mapOf(
    "/hello" to { _ -> HttpResponse.ok("Hello, World!") },
)))
```

Pipeline direction:
```
HEAD ↔ encoder ↔ decoder ↔ routing ↔ TAIL
Inbound:  HEAD → (encoder skipped) → decoder → routing
Outbound: routing → (decoder skipped) → encoder → HEAD
```

`RoutingHandler` is a terminal inbound handler: it matches `HttpRequestHead.path`
against a route map, invokes the handler, and writes the `HttpResponse` outbound.
Unmatched paths return 404 Not Found.

## Key Types

| Type | Notes |
|------|-------|
| `HttpRequest` | `method`, `uri`, `version`, `headers`, `body`. Factory: `get(uri)`, `post(uri, body)` |
| `HttpRequestHead` | Same as `HttpRequest` minus `body`. Computed: `path`, `queryString`, `isKeepAlive` |
| `HttpResponse` | `status`, `version`, `headers`, `body`. Factory: `ok(body)`, `notFound()` |
| `HttpResponseHead` | `status`, `version`, `headers` (no body) |
| `HttpHeaders` | Case-insensitive header map. `get(name)`, `set(name, value)`, `remove(name)`, `of(vararg pairs)` |
| `HttpMethod` | `GET`, `POST`, `PUT`, `DELETE`, `HEAD`, `OPTIONS`, `PATCH` |
| `HttpStatus` | Status code + reason phrase. Constants: `OK`, `NOT_FOUND`, `BAD_REQUEST`, etc. |
| `HttpVersion` | `HTTP_1_0`, `HTTP_1_1`. Use `.text` for wire format (`"HTTP/1.1"`) |
| `HttpHeaderName` | Typed header name constants (`CONTENT_TYPE`, `CONTENT_LENGTH`, `HOST`, etc.) |
| `HttpRequestDecoder` | Pipeline handler: `IoBuf` → `HttpRequestHead` |
| `HttpResponseEncoder` | Pipeline handler: `HttpResponse` → `IoBuf` |
| `RoutingHandler` | Terminal inbound handler: routes by path, writes `HttpResponse` outbound |

## Error Handling

| Exception | When thrown |
|-----------|-------------|
| `HttpParseException` | Malformed request/response line or headers |
| `HttpEofException` | Unexpected EOF while reading request line or headers |

# Package io.github.fukusaka.keel.codec.http

HTTP/1.1 parser, writer, and pipeline handlers (RFC 7230/7231).
Sync API via `kotlinx.io.Source`/`Sink`; suspend API via `BufferedSuspendSource`/`BufferedSuspendSink`.
