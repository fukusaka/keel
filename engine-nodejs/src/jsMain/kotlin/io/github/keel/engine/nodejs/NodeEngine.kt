package io.github.keel.engine.nodejs

import kotlin.js.Promise

class NodeEngine {

    private var _server: Server? = null

    val isListening: Boolean get() = _server?.listening == true

    fun bind(port: Int): Promise<Int> = Promise { resolve, reject ->
        val srv = Net.createServer { socket ->
            socket.on("data") { data: dynamic -> socket.write(data) }
            socket.on("error") { _: dynamic -> socket.destroy() }
        }
        srv.listen(port) {
            _server = srv
            resolve(srv.address().port as Int)
        }
        srv.on("error") { err: dynamic ->
            reject(Error(err.message as? String ?: "listen error"))
        }
    }

    fun runEchoLoop(): Promise<Unit> {
        val srv = checkNotNull(_server) { "call bind() first" }
        return Promise { resolve, _ ->
            srv.once("close") { _: dynamic -> resolve(Unit) }
        }
    }

    fun close(): Promise<Unit> {
        val srv = _server ?: return Promise.resolve(Unit)
        return Promise { resolve, _ ->
            srv.close { _: dynamic ->
                _server = null
                resolve(Unit)
            }
        }
    }
}
