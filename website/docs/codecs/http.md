---
sidebar_position: 1
---

# HTTP/1.1 Codec

The `:codec-http` module provides an RFC 7230/7231-compliant HTTP/1.1
parser and writer. It depends only on `kotlinx.io` and works on all
supported targets.

## Parsing

```kotlin
import io.github.keel.codec.http.*
import kotlinx.io.Buffer

val buf = Buffer()
buf.writeString("GET /hello HTTP/1.1\r\nHost: example.com\r\n\r\n")

val request: HttpRequest = parseRequest(buf)
println(request.method)   // HttpMethod("GET")
println(request.uri)      // "/hello"
println(request.version)  // HttpVersion.HTTP_1_1
```

## Writing

```kotlin
val response = HttpResponse(
    status = HttpStatus.OK,
    headers = HttpHeaders().apply {
        set(HttpHeaderName.CONTENT_TYPE, "text/plain")
        set(HttpHeaderName.CONTENT_LENGTH, "5")
    },
    body = "hello".encodeToByteArray(),
)

val buf = Buffer()
writeResponse(response, buf)
```

## RFC Compliance

- **obs-fold** (header line continuation) is rejected — RFC 7230 §3.2.6
- **Transfer-Encoding** takes priority over **Content-Length** when both
  are present — RFC 7230 §3.3.3 (HTTP Smuggling mitigation)
- **Set-Cookie** headers are never comma-joined — RFC 6265
- Chunked transfer encoding is supported for both parsing and writing

## Targets

`jvm` / `js (nodejs())` / `linuxX64` / `linuxArm64` / `macosArm64` / `macosX64`
