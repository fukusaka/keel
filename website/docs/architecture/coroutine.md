# Coroutine Mode

Coroutine mode is keel's suspend-based I/O API. Each accepted connection is handled as a `Channel` object with `suspend fun read()`, `write()`, and `flush()`. This model fits naturally into Kotlin coroutines and is the basis for Ktor integration via `keel-server-ktor`.

## When to use Coroutine mode

| Situation | Recommendation |
|---|---|
| Building a Ktor application | Use `keel-server-ktor` — Coroutine mode is wired automatically |
| Writing a custom coroutine-based server | Use `engine.bind()` + `server.accept()` directly |
| Maximum throughput with custom protocol | Consider [Pipeline mode](./pipeline.md) instead |

## Ktor integration

`keel-server-ktor` manages the entire Coroutine mode lifecycle internally. You do not call `engine.bind()` or `server.accept()` yourself — Ktor's `embeddedServer` call and the Ktor engine do this:

```kotlin
embeddedServer(Keel, port = 8080) {
    routing {
        get("/hello") {
            call.respondText("Hello from keel!")
        }
    }
}.start(wait = true)
```

Internally, `keel-server-ktor` calls `engine.bind()`, loops on `server.accept()`, and bridges each accepted `Channel` to Ktor's `ApplicationCall` pipeline using `channel.asBufferedSuspendSource()` and `channel.asSuspendSink()`.

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

    val ioDispatcher: CoroutineDispatcher  // optimal dispatcher for I/O on this channel
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

When `server.accept()` or `channel.read()` suspends, the calling coroutine releases its thread and the EventLoop is free to handle other connections. When I/O can proceed, the EventLoop resumes the coroutine on `channel.ioDispatcher`.

`keel-server-ktor` launches the connection handler on `ioDispatcher` so that I/O reads, request parsing, the Ktor pipeline, and response encoding all run on the same thread. The Ktor pipeline itself runs on `KeelApplicationEngine.Configuration.applicationDispatcher`: when null (the default) it collapses to `ioDispatcher`, so the internal `withContext(applicationDispatcher)` wrapper around the Ktor pipeline is a no-op (same dispatcher, elided by the coroutine runtime) — zero per-request context switches. Setting `applicationDispatcher = Dispatchers.Default` explicitly offloads the pipeline onto a work-stealing pool at the cost of one hop per request, useful when handlers routinely perform blocking work.

When writing custom server code and launching additional coroutines that perform I/O on the same channel, use `ioDispatcher` to keep them on the correct thread:

```kotlin
launch(channel.ioDispatcher) {
    // I/O on this channel runs on the optimal thread for the engine
}
```

Where `ioDispatcher` points depends on the engine:

| Engine | `ioDispatcher` (EventLoop) |
|---|---|
| epoll / kqueue / io_uring | Per-channel EventLoop thread (single pthread) |
| NIO (JVM) | `NioEventLoop` Selector thread |
| Netty (JVM) | `io.netty.channel.EventLoop` via `NettyEventLoopDispatcher` |
| NWConnection | Per-connection GCD serial dispatch queue via `NwConnectionQueueDispatcher` |
| Node.js | JS event loop (`Dispatchers.Unconfined`) |

Every engine resumes coroutines on the same thread that drives its native I/O primitive — epoll `epoll_wait`, kqueue `kevent`, io_uring CQE, Java NIO `Selector.select`, Netty `EventLoop.run`, GCD `dispatch_async`, Node.js microtask queue. The entire request pipeline (I/O read → HTTP parse → Ktor handler → response encode) runs on that one thread with no cross-thread handoff.

The trade-off: user handlers must not block the EventLoop. Blocking I/O — JDBC, `Thread.sleep`, filesystem reads on non-async APIs — stalls every other connection multiplexed on the same EventLoop until the blocking call completes. Wrap blocking calls in `withContext(Dispatchers.IO)` to hand them off to a cached thread pool:

```kotlin
get("/user/{id}") {
    val user = withContext(Dispatchers.IO) { jdbcTemplate.queryForObject(...) }
    call.respond(user)
}
```

CPU-intensive work should similarly be offloaded via `withContext(Dispatchers.Default)`.

## Performance

Coroutine mode incurs one coroutine per connection plus a context switch on each `read()` resume. For native engines (epoll, kqueue, io_uring) this overhead is low because resumed coroutines run directly on the EventLoop thread without cross-thread dispatch. See the [Engine Selection Guide](./engine-guide.md#performance-by-engine) for benchmark numbers.

For the highest possible throughput without Ktor, see [Pipeline Mode](./pipeline.md).
