# Module keel-testing-engine

In-memory `StreamEngine` test double — run any keel consumer with no OS
socket, no file descriptor, and no real network.

`InMemoryEngine` implements the same `bindPipeline` / `connect` contract as
the real engine modules (`keel-engine-nio`, `keel-engine-kqueue`, ...), so
anything that takes a `StreamEngine` — a `keelHttpServer`, the ktor
adapter, a raw pipeline, a `connect()`-based client — can be exercised
entirely in-process:

```kotlin
val engine = InMemoryEngine()
val server = engine.bindPipeline(InetSocketAddress("127.0.0.1", 0)) { channel ->
    channel.pipeline.addLast("echo", EchoHandler())
}
val client = engine.connect(server.localAddress)   // true in-memory loopback
```

## How it works

`bindPipeline` registers a listener keyed by its bind address (a synthetic
ephemeral port is assigned for `port == 0`). `connect` finds that listener
and builds a cross-wired transport pair: bytes written and flushed on one
side surface as inbound reads on the peer, and vice versa. The server side
runs the listener's pipeline initializer exactly like a real accepted
connection; the client side is returned as a Coroutine-mode `Channel`.

Delivery is synchronous — the I/O dispatcher is
`kotlinx.coroutines.Dispatchers.Unconfined`, so a flush hands bytes to the
peer inline on the calling coroutine with no EventLoop thread. Half-close
(`shutdownOutput`), EOF propagation, and inbound buffering ahead of a
not-yet-armed reader are modeled so lifecycle-sensitive code behaves as it
would on a kernel socket.

Only Pipeline-mode binding is supported: the accept-loop `bind` throws
`UnsupportedOperationException`.

`keel-testing-server-http` builds its in-process HTTP test harness on this
engine.

# Package io.github.fukusaka.keel.testing.engine

`InMemoryEngine` and its internal transport / channel / listener types.
