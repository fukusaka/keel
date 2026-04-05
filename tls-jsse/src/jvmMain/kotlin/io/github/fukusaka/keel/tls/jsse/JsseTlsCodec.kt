package io.github.fukusaka.keel.tls.jsse

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.unsafeBuffer
import io.github.fukusaka.keel.tls.TlsCodec
import io.github.fukusaka.keel.tls.TlsCodecResult
import io.github.fukusaka.keel.tls.TlsErrorCategory
import io.github.fukusaka.keel.tls.TlsException
import io.github.fukusaka.keel.tls.TlsResult
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * [TlsCodec] implementation backed by JSSE [SSLEngine].
 *
 * Maps SSLEngine's [wrap][SSLEngine.wrap]/[unwrap][SSLEngine.unwrap]
 * to TlsCodec's [protect]/[unprotect] with zero-copy buffer access
 * via [IoBuf.unsafeBuffer] (direct ByteBuffer).
 *
 * **Delegated tasks**: when SSLEngine returns [NEED_TASK][SSLEngineResult.HandshakeStatus.NEED_TASK],
 * tasks are executed inline on the current (EventLoop) thread. The
 * operation is NOT retried — the caller checks [SSLEngine.getHandshakeStatus]
 * to determine the next action.
 *
 * **Thread model**: single EventLoop thread, same as all TlsCodec implementations.
 */
class JsseTlsCodec internal constructor(
    private val engine: SSLEngine,
) : TlsCodec {

    private var closed = false
    private var handshakeComplete = false

    override val isHandshakeComplete: Boolean get() = handshakeComplete

    override val negotiatedProtocol: String?
        get() = engine.applicationProtocol?.ifEmpty { null }

    override val peerCertificates: List<ByteArray>
        get() = try {
            engine.session.peerCertificates.map { it.encoded }
        } catch (_: SSLPeerUnverifiedException) {
            emptyList()
        }

    override fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult {
        val src = ciphertext.unsafeBuffer
        val dst = plaintext.unsafeBuffer

        // Set ByteBuffer position/limit to match IoBuf reader/writer indices.
        src.position(ciphertext.readerIndex)
        src.limit(ciphertext.readerIndex + ciphertext.readableBytes)
        dst.position(plaintext.writerIndex)
        dst.limit(plaintext.writerIndex + plaintext.writableBytes)

        val result = runWithTasks { engine.unwrap(src, dst) }

        val bytesConsumed = result.bytesConsumed()
        val bytesProduced = result.bytesProduced()
        plaintext.writerIndex += bytesProduced

        return when (result.status) {
            SSLEngineResult.Status.OK -> {
                checkHandshakeFinished(result)
                // Use engine's current handshake status, not the result's —
                // result may be stale if NEED_TASK was handled by runWithTasks.
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP ->
                        TlsCodecResult(TlsResult.NEED_WRAP, bytesConsumed, bytesProduced)
                    else ->
                        TlsCodecResult(TlsResult.OK, bytesConsumed, bytesProduced)
                }
            }
            SSLEngineResult.Status.BUFFER_UNDERFLOW ->
                TlsCodecResult(TlsResult.NEED_MORE_INPUT, bytesConsumed, bytesProduced)
            SSLEngineResult.Status.BUFFER_OVERFLOW ->
                TlsCodecResult(TlsResult.BUFFER_OVERFLOW, bytesConsumed, bytesProduced)
            SSLEngineResult.Status.CLOSED ->
                TlsCodecResult(TlsResult.CLOSED, bytesConsumed, bytesProduced)
        }
    }

    override fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult {
        val src = plaintext.unsafeBuffer
        val dst = ciphertext.unsafeBuffer

        src.position(plaintext.readerIndex)
        src.limit(plaintext.readerIndex + plaintext.readableBytes)
        dst.position(ciphertext.writerIndex)
        dst.limit(ciphertext.writerIndex + ciphertext.writableBytes)

        val result = runWithTasks { engine.wrap(src, dst) }

        val bytesConsumed = result.bytesConsumed()
        val bytesProduced = result.bytesProduced()
        ciphertext.writerIndex += bytesProduced

        return when (result.status) {
            SSLEngineResult.Status.OK -> {
                checkHandshakeFinished(result)
                // Propagate NEED_WRAP so flushHandshakeResponse continues
                // sending all handshake flights (ServerHello, Certificate, etc.).
                when (engine.handshakeStatus) {
                    SSLEngineResult.HandshakeStatus.NEED_WRAP ->
                        TlsCodecResult(TlsResult.NEED_WRAP, bytesConsumed, bytesProduced)
                    else ->
                        TlsCodecResult(TlsResult.OK, bytesConsumed, bytesProduced)
                }
            }
            SSLEngineResult.Status.BUFFER_UNDERFLOW ->
                TlsCodecResult(TlsResult.NEED_MORE_INPUT, bytesConsumed, bytesProduced)
            SSLEngineResult.Status.BUFFER_OVERFLOW ->
                TlsCodecResult(TlsResult.BUFFER_OVERFLOW, bytesConsumed, bytesProduced)
            SSLEngineResult.Status.CLOSED ->
                TlsCodecResult(TlsResult.CLOSED, bytesConsumed, bytesProduced)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        engine.closeOutbound()
    }

    /**
     * Executes the SSLEngine operation and runs delegated tasks inline
     * if NEED_TASK is returned.
     *
     * Does NOT retry the operation after running tasks — the caller
     * uses [SSLEngine.getHandshakeStatus] to determine the next action.
     * Retrying would lose bytesConsumed from the first call: SunJSSE's
     * unwrap returns NEED_TASK with bytesConsumed > 0 (the TLS record
     * is consumed before the delegated task runs), so a retry sees an
     * empty source and returns bytesConsumed = 0.
     *
     * Verified via [SslEngineHandshakeTraceTest]: all three NEED_TASK
     * occurrences during a TLS 1.3 handshake had bytesConsumed > 0
     * (440, 127, 1180 bytes respectively).
     */
    private inline fun runWithTasks(
        operation: () -> SSLEngineResult,
    ): SSLEngineResult {
        val result = try {
            operation()
        } catch (e: SSLException) {
            throw TlsException(
                e.message ?: "SSLEngine error",
                TlsErrorCategory.PROTOCOL_ERROR,
                cause = e,
            )
        }

        if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.NEED_TASK) {
            runDelegatedTasks()
        }

        return result
    }

    private fun runDelegatedTasks() {
        while (true) {
            val task = engine.delegatedTask ?: break
            task.run()
        }
    }

    /**
     * Detects handshake completion from either the operation result or the
     * engine's current state.
     *
     * Checks the result first: FINISHED appears only once in the result of
     * the completing wrap/unwrap; subsequent calls return NOT_HANDSHAKING.
     * Falls back to engine state to cover the case where NEED_TASK was
     * handled inline by [runWithTasks] — the result still shows NEED_TASK
     * but the engine has already transitioned to NOT_HANDSHAKING.
     */
    private fun checkHandshakeFinished(result: SSLEngineResult) {
        if (handshakeComplete) return
        if (isHandshakeDone(result.handshakeStatus) || isHandshakeDone(engine.handshakeStatus)) {
            handshakeComplete = true
        }
    }

    private fun isHandshakeDone(status: SSLEngineResult.HandshakeStatus): Boolean =
        status == SSLEngineResult.HandshakeStatus.FINISHED ||
            status == SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING
}
