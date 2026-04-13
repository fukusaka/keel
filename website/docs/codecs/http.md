---
sidebar_position: 1
---

# HTTP/1.1 Codec

The `keel-codec-http` module provides an RFC 7230/7231-compliant HTTP/1.1
parser and writer. It depends on `keel-io`, `keel-core`, and `kotlinx.io`,
and works on all supported targets.

## Pipeline Mode

For Pipeline mode servers, add `HttpRequestDecoder` and `HttpResponseEncoder`
to the channel pipeline. **Outbound messages flow from tail toward head**, so
the encoder must be added before the decoder and handler:

```kotlin
engine.bindPipeline("0.0.0.0", 8080) { channel ->
    channel.pipeline.addLast("encoder", HttpResponseEncoder())
    channel.pipeline.addLast("decoder", HttpRequestDecoder())
    channel.pipeline.addLast("handler", MyHandler())
}
```

The resulting pipeline order is:

```
HEAD ↔ encoder ↔ decoder ↔ handler ↔ TAIL

Inbound:  HEAD → (encoder skipped) → decoder → handler
Outbound: handler → (decoder skipped) → encoder → HEAD
```

`HttpRequestDecoder` decodes inbound `IoBuf` bytes into a streaming message
sequence: `HttpRequestHead` → `HttpBody` × N → `HttpBodyEnd`. Every request
terminates with exactly one `HttpBodyEnd`, even no-body requests (`HttpBodyEnd.EMPTY`).
Both Content-Length and chunked transfer-encoding are supported.

`HttpResponseEncoder` serialises outbound response messages into `IoBuf`. It
accepts both the legacy `HttpResponse` type (complete body) and the streaming
sequence `HttpResponseHead` → `HttpBody` × N → `HttpBodyEnd`.

For handlers that need the full request body as `HttpRequest(body: ByteArray?)`,
insert `HttpBodyAggregator` between the decoder and your handler:

```kotlin
engine.bindPipeline("0.0.0.0", 8080) { channel ->
    channel.pipeline.addLast("encoder", HttpResponseEncoder())
    channel.pipeline.addLast("decoder", HttpRequestDecoder())
    channel.pipeline.addLast("aggregator", HttpBodyAggregator())
    channel.pipeline.addLast("handler", MyHandler())
}
```

For handlers that consume streaming body messages directly (no aggregation):

```kotlin
class MyHandler : InboundHandler {
    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequestHead -> { /* route by path */ }
            is HttpBodyEnd -> { msg.content.release(); /* emit response */ }
            is HttpBody -> { msg.content.release() }
        }
    }
}
```

## Coroutine Mode

For coroutine-based servers (non-Pipeline), obtain a `BufferedSuspendSource`
and `BufferedSuspendSink` from the `Channel` and use the suspend overloads:

```kotlin
import io.github.fukusaka.keel.codec.http.*
import io.github.fukusaka.keel.io.BufferedSuspendSink

val source = channel.asBufferedSuspendSource()
val sink = BufferedSuspendSink(
    channel.asSuspendSink(), channel.allocator, channel.supportsDeferredFlush
)
try {
    // Suspend variant — no runBlocking needed
    val head: HttpRequestHead = parseRequestHead(source)

    // Read body if Content-Length is present
    val body: ByteArray? = head.headers.contentLength?.let { len ->
        source.readByteArray(len.toInt())
    }

    // Build and write response
    val responseHeaders = HttpHeaders.build {
        add(HttpHeaderName.CONTENT_TYPE, "text/plain; charset=utf-8")
        add(HttpHeaderName.CONTENT_LENGTH, "5")
    }
    writeResponseHead(HttpStatus.OK, HttpVersion.HTTP_1_1, responseHeaders, sink)
    sink.write("hello".encodeToByteArray())
    sink.flush()
} finally {
    source.close()
    sink.close()
}
```

`parseRequestHead(BufferedSuspendSource)` and `parseResponseHead(BufferedSuspendSource)`
are the suspend overloads — the body is **not** consumed. Read the body bytes
manually from `source` using `source.readByteArray(length)` after parsing the
head.

`writeResponseHead(status, version, headers, BufferedSuspendSink)` writes the
status line and headers. Write the body with `sink.write(bytes)`, then call
`sink.flush()` to deliver all buffered data to the network.

## Parsing

Use `parseRequest` / `parseResponse` with a `kotlinx.io.Source`:

```kotlin
import io.github.fukusaka.keel.codec.http.*
import kotlinx.io.Buffer

val buf = Buffer()
buf.writeString("GET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n")

val request: HttpRequest = parseRequest(buf)
println(request.method)        // GET
println(request.uri)           // /hello
println(request.version.text)  // HTTP/1.1
println(request.path)          // /hello  (excludes query string)
```

Use `parseRequestHead` / `parseResponseHead` when you want to stream the body
separately — the head is returned and the body bytes remain in the source.

## Writing

Use `writeRequest` / `writeResponse` with a `kotlinx.io.Sink`:

```kotlin
// Factory method — sets Content-Type and Content-Length automatically
val response = HttpResponse.ok("hello")

// — or — construct manually
val response = HttpResponse(
    status = HttpStatus.OK,
    headers = HttpHeaders.build {
        add(HttpHeaderName.CONTENT_TYPE, "text/plain")
        add(HttpHeaderName.CONTENT_LENGTH, "5")
    },
    body = "hello".encodeToByteArray(),
)

val buf = Buffer()
writeResponse(response, buf)
```

`writeResponse` does **not** add `Content-Length` automatically. Either set it
in the headers or use a factory method (`HttpResponse.ok()`, `HttpResponse.of(status)`).

## Key Types

| Type | Notes |
|---|---|
| `HttpMessage` | Sealed interface — common supertype for all streaming pipeline messages |
| `HttpRequestHead` | `method`, `uri`, `version`, `headers`. Computed: `path`, `queryString`, `isKeepAlive`. Emitted by `HttpRequestDecoder` |
| `HttpResponseHead` | `status`, `version`, `headers`. Emitted to `HttpResponseEncoder` for streaming responses |
| `HttpBody` | Streaming body chunk wrapping an `IoBuf`. Receiver must call `content.release()` |
| `HttpBodyEnd` | Terminal body marker with optional trailer headers. `HttpBodyEnd.EMPTY` singleton for no-body/no-trailer |
| `HttpRequest` | Aggregated request: `method`, `uri`, `version`, `headers`, `body?`. Produced by `HttpBodyAggregator` |
| `HttpResponse` | Complete response: `status`, `version`, `headers`, `body?`. Factories: `ok()`, `notFound()`, `of(status)` |
| `HttpHeaders` | Case-insensitive store. `add()` / `set()` / `get()` / `getAll()` / `remove()`. `HttpHeaders.EMPTY` singleton |
| `HttpBodyAggregator` | Pipeline handler: buffers `HttpRequestHead` + `HttpBody` + `HttpBodyEnd` into `HttpRequest` |
| `HttpMethod` | Case-sensitive token. Constants: `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, etc. |

## Error Handling

| Exception | When thrown |
|---|---|
| `HttpParseException` | Malformed request line, invalid header, obs-fold, missing Host header, unsupported HTTP version, TE+CL conflict (Pipeline mode) |
| `HttpEofException` | Connection closed before a complete message was received |

Catch these in your pipeline's `onError` handler or in a surrounding `try`/`catch`.

## RFC Compliance

- **Host header** (RFC 7230 §5.4): HTTP/1.1 requests without a `Host` header are rejected
- **obs-fold** (header line continuation) is rejected — RFC 7230 §3.2.6
- **Transfer-Encoding + Content-Length conflict** (RFC 7230 §3.3.3):
  - `parseRequest` / `readBody`: Transfer-Encoding takes priority over Content-Length
  - `HttpRequestDecoder` (Pipeline mode): requests with both headers present are rejected with `HttpParseException`
- **Set-Cookie** headers are never comma-joined — RFC 6265
- Chunked transfer encoding is supported for both parsing and writing

## Targets

`jvm` / `js (nodejs())` / `linuxX64` / `linuxArm64` / `macosArm64` / `macosX64`
