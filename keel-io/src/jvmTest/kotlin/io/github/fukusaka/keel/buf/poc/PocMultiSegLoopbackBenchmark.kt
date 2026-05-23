package io.github.fukusaka.keel.buf.poc

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.buf.asByteBuffer
import io.github.fukusaka.keel.buf.poc.cand1.Cand1IoBufImpl
import io.github.fukusaka.keel.buf.poc.cand2.Cand2IoBufImpl
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import kotlin.concurrent.thread
import kotlin.test.Test

/**
 * Loopback throughput bench for the multi-seg IoBuf PoC.
 *
 * The in-process bench (`PocMultiSegBenchmark.runIovBuild`) measures
 * iov-build cost without the kernel syscall — that isolates the
 * cand1 / cand2 iteration-pattern delta. This bench layers the
 * actual `SocketChannel.write(ByteBuffer[])` syscall on top so the
 * delta is reported in the same throughput units the engine
 * write path will see in production.
 *
 * The setup uses a raw localhost socket pair rather than a full
 * `NioIoTransport`. The `writeMulti` impl on `NioIoTransport` is a
 * thin wrapper around the same iov-build + `socketChannel.write`
 * sequence; using the raw socket here avoids the SelectionKey /
 * EventLoop wiring that the bench does not need.
 *
 * Reads on the server side are eager and discarded — the bench
 * measures **write-side** throughput, which is where the candidate
 * difference would land in a real engine flush path.
 */
class PocMultiSegLoopbackBenchmark {

    private val segCap = 512
    private val maxCap = 64 * segCap
    private val payloadBytesPerSegment = segCap - 0 // fill segment fully
    private val segmentsPerBuffer = 16
    private val totalBytesPerBuffer = payloadBytesPerSegment * segmentsPerBuffer

    private fun openPair(): Pair<SocketChannel, SocketChannel> {
        val server = ServerSocketChannel.open()
        server.bind(InetSocketAddress("127.0.0.1", 0))
        val port = (server.localAddress as InetSocketAddress).port
        val client = SocketChannel.open()
        client.configureBlocking(true)
        client.connect(InetSocketAddress("127.0.0.1", port))
        val accepted = server.accept()
        accepted.configureBlocking(true)
        server.close()
        return client to accepted
    }

    /** Spins a reader thread that discards every byte until the channel closes. */
    private fun spinDrain(sc: SocketChannel): Thread = thread(start = true, name = "drain") {
        val sink = ByteBuffer.allocateDirect(64 * 1024)
        try {
            while (true) {
                sink.clear()
                val n = sc.read(sink)
                if (n <= 0) return@thread
            }
        } catch (e: Exception) {
            // socket closed
        }
    }

    private fun fillCand1(buf: Cand1IoBufImpl) {
        val chunk = ByteArray(payloadBytesPerSegment) { (it and 0xFF).toByte() }
        repeat(segmentsPerBuffer - 1) {
            buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        }
        repeat(segmentsPerBuffer) {
            buf.writeByteArray(chunk, 0, chunk.size)
        }
    }

    private fun fillCand2(buf: Cand2IoBufImpl) {
        val chunk = ByteArray(payloadBytesPerSegment) { (it and 0xFF).toByte() }
        repeat(segmentsPerBuffer - 1) {
            buf.appendSegment(extractSegment(DefaultAllocator.allocate(segCap)))
        }
        repeat(segmentsPerBuffer) {
            buf.writeByteArray(chunk, 0, chunk.size)
        }
    }

    @Test
    fun `report loopback writeMulti throughput`() {
        println("=== PoC multi-seg IoBuf loopback throughput (JVM) ===")
        println("    $segmentsPerBuffer segments x $payloadBytesPerSegment B = $totalBytesPerBuffer B / write")
        println()
        println("                                       ns/write   MB/s")
        runCand1()
        runCand2()
        runBaselineSingleBuf()
    }

    private fun runCand1() {
        val (client, server) = openPair()
        val drain = spinDrain(server)
        val buf = Cand1IoBufImpl(DefaultAllocator, segCap, maxCap)
        fillCand1(buf)
        val iovs = arrayOfNulls<ByteBuffer>(segmentsPerBuffer * 2)
        try {
            repeat(WARMUP) { writeMultiCand1(client, buf, iovs) }
            val start = System.nanoTime()
            repeat(ITERS) { writeMultiCand1(client, buf, iovs) }
            val ns = (System.nanoTime() - start) / ITERS
            val mbps = (totalBytesPerBuffer.toLong() * 1_000_000_000L) / (ns * 1024L * 1024L)
            println("  cand1 (callback iov-build)            %6d   %6d".format(ns, mbps))
        } finally {
            buf.close()
            client.close()
            drain.join(1000)
        }
    }

    private fun runCand2() {
        val (client, server) = openPair()
        val drain = spinDrain(server)
        val buf = Cand2IoBufImpl(DefaultAllocator, segCap, maxCap)
        fillCand2(buf)
        val iovs = arrayOfNulls<ByteBuffer>(segmentsPerBuffer * 2)
        try {
            repeat(WARMUP) { writeMultiCand2(client, buf, iovs) }
            val start = System.nanoTime()
            repeat(ITERS) { writeMultiCand2(client, buf, iovs) }
            val ns = (System.nanoTime() - start) / ITERS
            val mbps = (totalBytesPerBuffer.toLong() * 1_000_000_000L) / (ns * 1024L * 1024L)
            println("  cand2 (list iov-build)                %6d   %6d".format(ns, mbps))
        } finally {
            buf.close()
            client.close()
            drain.join(1000)
        }
    }

    /**
     * Baseline: send the same total bytes as one contiguous direct
     * [ByteBuffer] via a single `SocketChannel.write`. Establishes
     * the no-multi-seg ceiling — the multi-seg candidates should be
     * within syscall noise of this floor since scatter-gather is a
     * single syscall too, only the iov-build cost differs.
     */
    private fun runBaselineSingleBuf() {
        val (client, server) = openPair()
        val drain = spinDrain(server)
        val bb = ByteBuffer.allocateDirect(totalBytesPerBuffer)
        for (i in 0 until totalBytesPerBuffer) bb.put((i and 0xFF).toByte())
        try {
            repeat(WARMUP) {
                bb.flip()
                client.write(bb)
            }
            val start = System.nanoTime()
            repeat(ITERS) {
                bb.flip()
                client.write(bb)
            }
            val ns = (System.nanoTime() - start) / ITERS
            val mbps = (totalBytesPerBuffer.toLong() * 1_000_000_000L) / (ns * 1024L * 1024L)
            println("  baseline (single direct ByteBuffer)   %6d   %6d".format(ns, mbps))
        } finally {
            client.close()
            drain.join(1000)
        }
    }

    /** Inlined version of `NioIoTransport.writeMulti(Cand1IoBuf)`. */
    private fun writeMultiCand1(sc: SocketChannel, buf: Cand1IoBufImpl, iovs: Array<ByteBuffer?>) {
        buf.readerIndex = 0 // rewind so we can re-send the same payload
        var count = 0
        buf.forEachReadableSegment { mem, off, len ->
            val view = mem.asByteBuffer().duplicate()
            view.position(off)
            view.limit(off + len)
            iovs[count] = view
            count++
        }
        @Suppress("UNCHECKED_CAST")
        sc.write(iovs as Array<ByteBuffer>, 0, count)
    }

    /** Inlined version of `NioIoTransport.writeMulti(Cand2IoBuf)`. */
    private fun writeMultiCand2(sc: SocketChannel, buf: Cand2IoBufImpl, iovs: Array<ByteBuffer?>) {
        buf.readerIndex = 0
        val list = buf.readableSegments()
        val n = list.size
        for (i in 0 until n) {
            val range = list[i]
            val view = range.memory!!.asByteBuffer().duplicate()
            view.position(range.offset)
            view.limit(range.offset + range.length)
            iovs[i] = view
        }
        @Suppress("UNCHECKED_CAST")
        sc.write(iovs as Array<ByteBuffer>, 0, n)
    }

    companion object {
        private const val WARMUP = 1_000
        private const val ITERS = 20_000
    }
}
