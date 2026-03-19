package io.github.keel.engine.nodejs

@JsModule("net")
@JsNonModule
external object Net {
    fun createServer(connectionListener: (socket: Socket) -> Unit): Server
    fun createConnection(port: Int, host: String): Socket
    fun createConnection(port: Int): Socket
}

external interface NodeEventEmitter {
    fun on(event: String, listener: (arg: dynamic) -> Unit): dynamic
    fun once(event: String, listener: (arg: dynamic) -> Unit): dynamic
}

external interface Server : NodeEventEmitter {
    fun listen(port: Int, callback: () -> Unit = definedExternally): Server
    fun close(callback: ((dynamic) -> Unit) = definedExternally): Server
    val listening: Boolean
    fun address(): dynamic
}

external interface Socket : NodeEventEmitter {
    fun write(data: dynamic): Boolean
    fun end(): Socket
    fun destroy(): Socket
    val remoteAddress: String?
    val remotePort: Int?
    val localAddress: String?
    val localPort: Int?
}
