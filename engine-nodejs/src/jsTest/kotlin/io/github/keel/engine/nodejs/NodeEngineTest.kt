package io.github.keel.engine.nodejs

import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NodeEngineTest {

    @Test
    fun engineCreatesWithoutError() {
        NodeEngine()
    }

    @Test
    fun bindReturnsValidPort(): Promise<Unit> = Promise { resolve, reject ->
        val engine = NodeEngine()
        engine.bind(0).then { port ->
            if (port > 0) {
                engine.close().then { _ -> resolve(Unit) }
            } else {
                reject(AssertionError("assigned port must be positive, got $port"))
            }
        }
    }

    @Test
    fun serverIsListeningAfterBind(): Promise<Unit> = Promise { resolve, reject ->
        val engine = NodeEngine()
        engine.bind(0).then { _ ->
            if (engine.isListening) {
                engine.close().then { _ -> resolve(Unit) }
            } else {
                reject(AssertionError("server must be listening after bind"))
            }
        }
    }

    @Test
    fun echoServerEchoesDataOverLoopback(): Promise<Unit> = Promise { resolve, reject ->
        val engine = NodeEngine()
        engine.bind(0).then { port ->
            val client = Net.createConnection(port, "127.0.0.1")
            client.on("connect") { _: dynamic -> client.write("hello") }
            client.on("data") { data: dynamic ->
                // dynamic に引数付きで呼ぶと JS ネイティブメソッドにディスパッチされる
                val received = data.toString("utf8") as String
                if (received == "hello") {
                    client.destroy()
                    engine.close().then { _ -> resolve(Unit) }
                } else {
                    client.destroy()
                    reject(AssertionError("expected 'hello', got '$received'"))
                }
            }
            client.on("error") { err: dynamic ->
                reject(Error(err.message as? String ?: "client error"))
            }
        }
    }
}
