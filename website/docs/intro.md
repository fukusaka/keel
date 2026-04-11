---
sidebar_position: 1
---

# Getting Started

keel is a Kotlin Multiplatform network I/O engine library. It wraps platform-native I/O syscalls — epoll on Linux, kqueue on macOS, io_uring on modern Linux kernels — behind a single `StreamEngine` interface that works identically across JVM, Native, and JS targets.

## What keel is — and is not

| Question | Answer |
|---|---|
| What is keel? | A KMP library that provides a unified `StreamEngine` interface for socket I/O across Linux (epoll, io_uring), macOS (kqueue, NWConnection), JVM (NIO, Netty), and JS (Node.js). |
| Does keel run on Kotlin/Native? | Yes. keel was built for Kotlin/Native first — epoll, kqueue, io_uring, and NWConnection are all native engines with no JVM dependency. |
| Can keel be used as a Ktor backend? | Yes. `keel-ktor-engine` plugs keel into Ktor as a server engine. You write Ktor routes; keel moves the bytes. |
| Is keel a web framework? | No. keel is a transport layer — it moves bytes on sockets. Use [Ktor](https://ktor.io) on top for routing, request parsing, and HTTP semantics. |
| Does keel replace Netty? | No. On JVM, `keel-engine-netty` uses Netty as its I/O backend. On Kotlin/Native, Netty does not run at all — keel calls OS syscalls directly. keel and Netty operate at different abstraction levels. |

## Architecture

keel's central value is **one `StreamEngine` interface across seven engines**. Codec and TLS sit above the interface as optional layers shared across all engines:

```
  Your App / Ktor
        │
  ┌─────┴─────────────────────────────────────────────┐
  │  Codec (HTTP, WebSocket)                          │  optional
  │  TLS (JSSE · OpenSSL · Mbed TLS · AWS-LC · ...)  │  optional
  ├───────────────────────────────────────────────────┤
  │               StreamEngine                        │  ← keel's unified interface
  ├──────┬──────┬───────┬────────┬───────┬──────┬─────┤
  │ NIO  │Netty │ epoll │io_uring│kqueue │  NW  │Node │
  │    JVM     │      Linux      │    macOS     │  JS │
  └──────┴──────┴───────┴────────┴───────┴──────┴─────┘
```

## Quick Start

keel is not yet published to Maven Central. Build and publish to local Maven first:

```bash
git clone https://github.com/fukusaka/keel.git
cd keel && ./gradlew publishToMavenLocal
```

### Coroutine mode with Ktor

```kotlin
// build.gradle.kts
repositories { mavenLocal() }

dependencies {
    implementation("io.github.fukusaka.keel:keel-ktor-engine:0.3.0")
    implementation("io.ktor:ktor-server-core:3.4.1")
}
```

```kotlin
import io.github.fukusaka.keel.ktor.Keel
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

fun main() {
    embeddedServer(Keel, port = 8080) {
        routing {
            get("/hello") {
                call.respondText("Hello from keel!")
            }
        }
    }.start(wait = true)
}
```

Which engine runs depends on which `keel-engine-*` dependency is on the classpath at compile time. `keel-ktor-engine` uses the engine module present in each target's dependency set. See the [Engine Selection Guide](./architecture/engine-guide.md) for how to configure each target.

### Pipeline mode (without Ktor)

```kotlin
// build.gradle.kts
dependencies {
    implementation("io.github.fukusaka.keel:keel-engine-epoll:0.3.0")  // or keel-engine-kqueue, keel-engine-nio, etc.
    implementation("io.github.fukusaka.keel:keel-codec-http:0.3.0")
}
```

```kotlin
class HelloHandler : TypedChannelInboundHandler<HttpRequest>() {
    override fun onMessage(ctx: ChannelHandlerContext, msg: HttpRequest) {
        ctx.write(HttpResponse(HttpStatus.OK, body = "Hello!".encodeToByteArray()))
        ctx.flush()
    }
}

val engine = EpollEngine()  // or KqueueEngine, NioEngine, etc.
engine.bindPipeline("0.0.0.0", 8080) { channel ->
    channel.pipeline
        .addLast("decoder", HttpRequestDecoder())
        .addLast("handler", HelloHandler())
        .addLast("encoder", HttpResponseEncoder())
}
```

## Two I/O Modes

Event-driven I/O — epoll, kqueue, io_uring on native platforms, and NIO Selector on JVM — notifies the application when I/O can proceed: data has arrived, the send buffer has space, or a new connection is waiting to be accepted. There are two fundamentally different ways to expose this to application code:

**Non-blocking sequential model** — The I/O thread resumes a suspended handler when I/O can proceed. Your code reads sequentially — like synchronous code — without blocking a thread. In Kotlin this is `suspend fun`; the same pattern appears as `async`/`await` in Python, C#, and JavaScript. Unlike the traditional blocking model (one OS thread per connection blocked on `read()`), this scales to thousands of concurrent connections on a single thread.

**Push model** — When I/O can proceed, the engine calls your handler directly on the I/O thread. No suspend, no context switch — just a chain of function calls. This is the model Netty's `ChannelPipeline` uses, and it fits naturally with event-driven I/O.

keel provides both:

| | Coroutine Mode | Pipeline Mode |
|---|---|---|
| Model | Non-blocking sequential | Push / event-driven |
| API | `suspend fun read() / write()` | `ChannelPipeline` handler chain |
| Concurrency unit | One coroutine per connection | Callbacks on EventLoop thread |
| How to use | `keel-ktor-engine` or `engine.bind()` | `engine.bindPipeline(...)` |
| Best for | Application servers with Ktor | High-throughput custom protocol servers |

**Coroutine mode** is what you get when using `keel-ktor-engine`. It integrates with all Ktor plugins and is the right choice for most applications.

**Pipeline mode** follows the push model. keel-core provides `ChannelPipeline` — a Netty-inspired handler chain that all engines implement. You configure it via `engine.bindPipeline()`, placing decoders, routers, and encoders as handlers. I/O callbacks run on the engine's EventLoop thread without coroutine context switches.

See [Coroutine Mode](./architecture/coroutine.md) and [Pipeline Mode](./architecture/pipeline.md) for details.

## Modules

### Core

| Module | What it provides |
|---|---|
| `keel-core` | `StreamEngine`, `Channel`, `Server`, `BindConfig`, `Logger` |
| `keel-io` | `IoBuf`, `SuspendSource`, `SuspendSink`, `BufferAllocator` |
| `keel-tls` | `TlsConfig`, `TlsInstaller`, certificate utilities (TLS interface definitions; pulled in transitively) |
| `keel-native-posix` | Shared POSIX socket utilities (internal use by Native engines) |

### Engines (select one per target platform)

| Module | Platform | Mechanism |
|---|---|---|
| `keel-engine-nio` | JVM | java.nio.Selector |
| `keel-engine-netty` | JVM | Netty 4.2 |
| `keel-engine-epoll` | Linux | epoll |
| `keel-engine-io-uring` | Linux (kernel 5.1+) | io_uring |
| `keel-engine-kqueue` | macOS | kqueue |
| `keel-engine-nwconnection` | macOS / iOS (Apple) | Network.framework |
| `keel-engine-nodejs` | JS (Node.js) | Node.js net/tls |

### TLS backends (optional)

| Module | Platform | Library |
|---|---|---|
| `keel-tls-jsse` | JVM | JSSE (JDK standard) |
| `keel-tls-mbedtls` | Native | Mbed TLS 4.x ¹ |
| `keel-tls-openssl` | Native | OpenSSL 3.x ¹ |
| `keel-tls-awslc` | Native | AWS-LC (BoringSSL fork) ¹ |

¹ Requires `-Ptls` build flag.

`keel-engine-netty`, `keel-engine-nwconnection`, and `keel-engine-nodejs` handle TLS without a `keel-tls-*` module.

### Ktor adapter and Codecs

| Module | What it provides |
|---|---|
| `keel-ktor-engine` | Ktor server engine adapter |
| `keel-codec-http` | HTTP/1.1 parser / writer (RFC 7230/7231) |
| `keel-codec-websocket` | WebSocket framing (RFC 6455) |

## Requirements

- Kotlin 2.3.20+
- JVM 21+ (JVM targets)
- Linux 4.5+ (epoll), 5.1+ (io_uring)
- macOS 10.14+ (kqueue / NWConnection)
- TLS (`-Ptls` build): OpenSSL 3.x · Mbed TLS 4.x · AWS-LC

## License

Apache 2.0 — Copyright 2026 fukusaka
