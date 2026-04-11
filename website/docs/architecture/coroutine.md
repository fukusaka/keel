# Coroutine Mode

Coroutine mode is keel's suspend-based I/O API. Each accepted connection is handled as a `Channel` object with `suspend fun read()`, `write()`, and `flush()`. This model fits naturally into Kotlin coroutines and is the basis for Ktor integration via `keel-ktor-engine`.

## When to use Coroutine mode

| Situation | Recommendation |
|---|---|
| Building a Ktor application | Use `keel-ktor-engine` — Coroutine mode is wired automatically |
| Writing a custom coroutine-based server | Use `engine.bind()` + `server.accept()` directly |
| Maximum throughput with custom protocol | Consider [Pipeline mode](./pipeline.md) instead |

## Ktor integration

`keel-ktor-engine` manages the entire Coroutine mode lifecycle internally. You do not call `engine.bind()` or `server.accept()` yourself — Ktor's `embeddedServer` call and the Ktor engine do this:

```kotlin
embeddedServer(Keel, port = 8080) {
    routing {
        get("/hello") {
            call.respondText("Hello from keel!")
        }
    }
}.start(wait = true)
```

Internally, `keel-ktor-engine` calls `engine.bind()`, loops on `server.accept()`, and bridges each accepted `Channel` to Ktor's `ApplicationCall` pipeline using `channel.asBufferedSuspendSource()` and `channel.asSuspendSink()`.

## How Coroutine mode works

```
engine.bind()                    → Server (listening socket)
    └── server.accept()          → Channel (one per accepted connection)
            ├── channel.read()   → suspend until data arrives, fill IoBuf
            ├── channel.write()  → buffer the data
            ├── channel.flush()  → suspend until buffered data is sent
            └── channel.close()  → close the connection
```

Each call to `server.accept()` suspends until a new connection arrives. Each `channel.read()` suspends until data is available from the peer. The engine drives I/O events on a background EventLoop; coroutines resume automatically when I/O can proceed.

## Direct usage

```kotlin
import io.github.fukusaka.keel.core.*

val engine: StreamEngine = EpollEngine()  // or KqueueEngine, NioEngine, etc.

val server = engine.bind("0.0.0.0", 8080)
println("Listening on ${server.localAddress}")

while (true) {
    val channel = server.accept()         // suspends until a connection arrives
    launch {                              // handle each connection in its own coroutine
        val buf = channel.allocator.allocate(4096)  // one buffer per connection, reused across reads
        try {
            while (true) {
                buf.clear()               // reset readerIndex and writerIndex to 0
                val n = channel.read(buf) // suspends until data arrives; -1 on EOF
                if (n == -1) break
                channel.write(buf)        // echo: write received data back (write() retains buf internally)
                channel.flush()           // suspends until data is sent
            }
        } finally {
            buf.release()
            channel.close()
        }
    }
}
```

## Channel interface

```kotlin
interface Channel : AutoCloseable {
    val allocator: BufferAllocator         // allocator for this channel's engine
    val remoteAddress: SocketAddress?
    val localAddress: SocketAddress?
    val isOpen: Boolean                    // true while the transport is open
    val isActive: Boolean                  // true while connected and ready for I/O

    suspend fun read(buf: IoBuf): Int      // fills buf; returns byte count, or -1 on EOF
    suspend fun write(buf: IoBuf): Int     // buffers outbound data; returns byte count
    suspend fun flush()                    // sends all buffered data, suspends until complete
    fun shutdownOutput()                   // TCP FIN — signals no more output; read side stays open
    override fun close()                   // closes both sides, releases resources

    val coroutineDispatcher: CoroutineDispatcher  // optimal dispatcher for I/O on this channel
    val appDispatcher: CoroutineDispatcher        // dispatcher for application-level processing
}
```

`read()` fills the provided `buf` starting at `buf.writerIndex` and advances it by the number of bytes read. The caller owns and manages `buf`'s lifecycle — allocate before reading, release when done.

`write()` internally retains `buf` and records the byte range at call time; `buf.readerIndex` is advanced immediately. The retained reference is released when `flush()` completes. If reusing the same buffer, wait for `flush()` to complete before overwriting its contents.

## Codec bridge

Coroutine mode integrates with keel's codec layer via `asSuspendSource()` and `asSuspendSink()`. `BufferedSuspendSource` and `BufferedSuspendSink` wrap these for line-oriented and byte-oriented codec access. This is how `keel-codec-http` and `keel-codec-websocket` consume and produce data:

```kotlin
// Codec-layer reading:
val source: BufferedSuspendSource = channel.asBufferedSuspendSource()

// Codec-layer writing:
val sink = BufferedSuspendSink(channel.asSuspendSink(), channel.allocator)

// Parse an HTTP request head from the channel:
val requestHead = parseRequestHead(source)
```

## Backpressure

Multiple `write()` calls buffer outbound data locally without sending. A single `flush()` submits all buffered data to the OS — enabling gather-write (`writev`) when engines support it — and suspends until the OS acknowledges. TCP flow control propagates naturally: when the peer's receive buffer is full, `flush()` suspends until space is available.

```kotlin
// Multiple writes, one flush — enables gather-write optimization:
channel.write(headersBuf)
channel.write(bodyBuf)
channel.flush()  // sends headers + body together when possible
```

## EventLoop interaction

When `server.accept()` or `channel.read()` suspends, the calling coroutine releases its thread and the EventLoop is free to handle other connections. When I/O can proceed, the EventLoop resumes the coroutine on `channel.coroutineDispatcher`.

`keel-ktor-engine` uses both dispatchers internally: it launches the connection handler on `coroutineDispatcher` so that I/O reads and request parsing run on the EventLoop thread, then switches to `appDispatcher` via `withContext` to execute the Ktor pipeline (routing, response generation). When using `keel-ktor-engine`, this is managed automatically.

When writing custom server code and launching additional coroutines that perform I/O on the same channel, use `coroutineDispatcher` to keep them on the correct thread:

```kotlin
launch(channel.coroutineDispatcher) {
    // I/O on this channel runs on the optimal thread for the engine
}
```

Where these dispatchers point depends on the engine:

| Engine | `coroutineDispatcher` | `appDispatcher` |
|---|---|---|
| epoll / kqueue / io_uring | EventLoop thread | EventLoop thread |
| NIO (JVM) | EventLoop thread | `Dispatchers.Default` |
| Netty / NWConnection / Node.js | `Dispatchers.Default` | `Dispatchers.Default` |

**Native engines (epoll, kqueue, io_uring)**: both dispatchers return the EventLoop thread. I/O reads, request parsing, and the Ktor pipeline all run on the same thread — no cross-thread handoff, no wakeup syscalls when code is already on the EventLoop thread. The trade-off: coroutine code must not block; CPU-intensive work should be offloaded to `Dispatchers.Default`.

**NIO (JVM)**: `coroutineDispatcher` returns the EventLoop thread; `appDispatcher` returns `Dispatchers.Default`. NIO runs fewer EventLoop threads than there are connections, so each EventLoop handles a fixed subset of connections with no way to rebalance. `Dispatchers.Default` (ForkJoinPool) actively solves this: work-stealing continuously redistributes tasks across all cores regardless of which EventLoop accepted the connection.

**Netty / NWConnection / Node.js**: keel does not own the EventLoop for these engines. Both dispatchers return `Dispatchers.Default`, deferring to each engine's own threading model.

## Performance

Coroutine mode incurs one coroutine per connection plus a context switch on each `read()` resume. For native engines (epoll, kqueue, io_uring) this overhead is low because resumed coroutines run directly on the EventLoop thread without cross-thread dispatch. See the [Engine Selection Guide](./engine-guide.md#performance-by-engine) for benchmark numbers.

For the highest possible throughput without Ktor, see [Pipeline Mode](./pipeline.md).
