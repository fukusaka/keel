package io.github.fukusaka.keel.engine.nodejs

@JsModule("net")
@JsNonModule
external object Net {
    fun createServer(connectionListener: (socket: Socket) -> Unit): Server
    fun createConnection(port: Int, host: String): Socket
    fun createConnection(port: Int): Socket

    /**
     * Path-based connect for Unix-domain sockets:
     * `{ path: "/tmp/foo.sock" }` or `{ path: "\u0000abstract-name" }`
     * on Linux for abstract-namespace sockets.
     */
    fun createConnection(options: dynamic): Socket
}

external interface NodeEventEmitter {
    fun on(event: String, listener: (arg: dynamic) -> Unit): dynamic
    fun once(event: String, listener: (arg: dynamic) -> Unit): dynamic
}

external interface Server : NodeEventEmitter {
    fun listen(port: Int, callback: () -> Unit = definedExternally): Server
    fun listen(options: dynamic, callback: () -> Unit = definedExternally): Server
    fun close(callback: ((dynamic) -> Unit) = definedExternally): Server
    val listening: Boolean
    fun address(): dynamic
}

external interface Socket : NodeEventEmitter {
    fun write(data: dynamic): Boolean
    fun end(): Socket
    fun destroy(): Socket
    fun setNoDelay(noDelay: Boolean = definedExternally): Socket
    fun setKeepAlive(enable: Boolean = definedExternally, initialDelay: Int = definedExternally): Socket

    // `cork()` forces buffering of all subsequent `write()` calls until
    // `uncork()` (or `end()`) is called; on `uncork()`, the buffered writes
    // are flushed together — Node uses the `Socket._writev` path, which
    // maps to a single `writev(2)` on POSIX, coalescing many per-frame
    // sends into one gather send.
    fun cork(): Unit

    fun uncork(): Unit
    val remoteAddress: String?
    val remotePort: Int?
    val localAddress: String?
    val localPort: Int?
}
