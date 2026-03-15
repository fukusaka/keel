package io.github.keel.engine.nio

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel

class NioEngine : AutoCloseable {

    private val selector: Selector = Selector.open()

    fun bind(port: Int): ServerSocketChannel {
        val ch = ServerSocketChannel.open()
        ch.configureBlocking(false)
        ch.bind(InetSocketAddress(port))
        ch.register(selector, SelectionKey.OP_ACCEPT)
        return ch
    }

    fun runEchoLoop(serverChannel: ServerSocketChannel, maxEvents: Int = Int.MAX_VALUE) {
        val buf = ByteBuffer.allocate(4096)
        var processed = 0

        while (processed < maxEvents) {
            val n = selector.select(1000)
            if (n == 0) continue

            val iter = selector.selectedKeys().iterator()
            while (iter.hasNext()) {
                val key = iter.next()
                iter.remove()

                when {
                    key.isAcceptable -> acceptAndRegister(serverChannel)
                    key.isReadable   -> echoOnce(key, buf)
                }
                processed++
                if (processed >= maxEvents) break
            }
        }
    }

    private fun acceptAndRegister(serverChannel: ServerSocketChannel) {
        val client = serverChannel.accept() ?: return
        client.configureBlocking(false)
        client.register(selector, SelectionKey.OP_READ)
    }

    private fun echoOnce(key: SelectionKey, buf: ByteBuffer) {
        val ch = key.channel() as SocketChannel
        buf.clear()
        val n = ch.read(buf)
        when {
            n > 0 -> { buf.flip(); ch.write(buf) }
            n < 0 -> { key.cancel(); ch.close() }
        }
    }

    override fun close() {
        selector.close()
    }
}
