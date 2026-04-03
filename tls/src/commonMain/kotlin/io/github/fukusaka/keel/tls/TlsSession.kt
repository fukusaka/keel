package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.IoBuf

/**
 * An active TLS connection session.
 *
 * Wraps an underlying transport (POSIX fd, SocketChannel, NWConnection, etc.)
 * and provides encrypted read/write operations. The caller must call [handshake]
 * before [read] or [write].
 *
 * **Lifecycle**: create via [TlsEngine] → [handshake] → [read]/[write] → [close].
 *
 * **Thread model**: instances are confined to a single EventLoop thread
 * (same as the underlying transport). Not thread-safe.
 *
 * **Buffer ownership**: [read] writes decrypted data into the caller's buffer.
 * [write] reads plaintext from the caller's buffer and encrypts it. The session
 * does not retain or release buffers — no ownership transfer occurs.
 *
 * **Transport ownership**: the session does NOT own the underlying transport.
 * [close] sends the TLS close_notify alert but does not close the fd/channel.
 * The caller is responsible for closing the transport after closing the session.
 *
 * **QUIC future**: when QUIC support is added (Phase 12+), a `TlsHandshaker`
 * super-interface will be extracted for handshake + key derivation without
 * record-layer I/O. See `research.md` §12–§14.
 */
interface TlsSession {

    /**
     * Performs the TLS handshake.
     *
     * For blocking transports, this blocks until the handshake completes.
     * For non-blocking transports (EventLoop-integrated), this suspends
     * on `WANT_READ`/`WANT_WRITE` until the handshake completes.
     *
     * Must be called exactly once before [read] or [write].
     *
     * @throws TlsException on handshake failure (e.g. certificate verification,
     *         protocol mismatch, peer rejected).
     */
    suspend fun handshake()

    /**
     * Reads decrypted data into [buf].
     *
     * Reads from the TLS session's internal decryption buffer. If no decrypted
     * data is available, reads encrypted data from the transport, decrypts it,
     * and writes the plaintext into [buf].
     *
     * @param buf Buffer to write decrypted data into. Data is written starting
     *            at [IoBuf.writerIndex].
     * @return Number of bytes read, or -1 on EOF (peer sent close_notify).
     * @throws TlsException on read failure.
     */
    suspend fun read(buf: IoBuf): Int

    /**
     * Writes plaintext data from [buf], encrypting it for transmission.
     *
     * Reads plaintext from [buf] starting at [IoBuf.readerIndex], encrypts it,
     * and sends the ciphertext to the transport.
     *
     * @param buf Buffer containing plaintext data to encrypt and send.
     * @return Number of plaintext bytes consumed from [buf].
     * @throws TlsException on write failure.
     */
    suspend fun write(buf: IoBuf): Int

    /**
     * The ALPN protocol negotiated during handshake, or null.
     *
     * Available after [handshake] completes. Returns null if ALPN was not
     * configured in [TlsConfig.alpnProtocols] or if the peer did not
     * select a protocol.
     */
    val negotiatedProtocol: String?

    /**
     * Sends the TLS close_notify alert and releases TLS-layer resources.
     *
     * Does NOT close the underlying transport (fd, channel, socket).
     * The caller must close the transport separately after this call.
     *
     * Safe to call multiple times (idempotent). Calling [read] or [write]
     * after [close] results in undefined behavior.
     */
    fun close()
}
