package io.github.fukusaka.keel.tls

import io.github.fukusaka.keel.buf.IoBuf

/**
 * Buffer-to-buffer TLS record protection codec.
 *
 * Applies or removes cryptographic protection (AEAD encryption + authentication)
 * on TLS records. Naming follows RFC 8446 (TLS 1.3) §5.2 "Record Payload
 * Protection" and RFC 9001 (QUIC-TLS) §5 "Packet Protection".
 *
 * Unlike transport-integrated APIs (OpenSSL `SSL_read`/`SSL_write`, Go `tls.Conn`),
 * this interface operates on buffers without owning the transport. This enables
 * integration as a Pipeline handler where the transport is managed by the engine.
 *
 * **Thread model**: instances are confined to a single EventLoop thread.
 * All methods are synchronous (non-suspend) and MUST NOT block.
 *
 * **Handshake**: driven implicitly through [unprotect]/[protect] calls.
 * When [unprotect] returns [TlsResult.NEED_WRAP], the caller must call
 * [protect] with an empty plaintext to produce the handshake response,
 * then retry [unprotect]. Check [isHandshakeComplete] after each call.
 *
 * **Lifecycle**: create via [TlsCodecFactory] → [unprotect]/[protect] → [close].
 */
interface TlsCodec {

    /**
     * Removes TLS record protection: decrypts and verifies ciphertext.
     *
     * Reads from [ciphertext] starting at its readerIndex and writes decrypted
     * plaintext into [plaintext] starting at its writerIndex. The implementation
     * advances [plaintext]'s writerIndex by the number of bytes produced.
     * The caller advances [ciphertext]'s readerIndex by [TlsCodecResult.bytesConsumed].
     *
     * @param ciphertext Input buffer containing TLS records from the network.
     * @param plaintext Output buffer for decrypted application data.
     * @return Result with status, bytes consumed from [ciphertext],
     *         and bytes produced into [plaintext].
     */
    fun unprotect(ciphertext: IoBuf, plaintext: IoBuf): TlsCodecResult

    /**
     * Applies TLS record protection: encrypts and authenticates plaintext.
     *
     * Reads from [plaintext] starting at its readerIndex and writes encrypted
     * TLS records into [ciphertext] starting at its writerIndex. The implementation
     * advances [ciphertext]'s writerIndex by the number of bytes produced.
     * The caller advances [plaintext]'s readerIndex by [TlsCodecResult.bytesConsumed].
     *
     * During handshake, call with an empty [plaintext] (readableBytes == 0)
     * to produce handshake messages (e.g., ServerHello, Finished).
     *
     * @param plaintext Input buffer containing application data to encrypt.
     * @param ciphertext Output buffer for encrypted TLS records.
     * @return Result with status, bytes consumed from [plaintext],
     *         and bytes produced into [ciphertext].
     */
    fun protect(plaintext: IoBuf, ciphertext: IoBuf): TlsCodecResult

    /** True after the TLS handshake has completed successfully. */
    val isHandshakeComplete: Boolean

    /**
     * The ALPN protocol negotiated during handshake, or null.
     *
     * Available after [isHandshakeComplete] becomes true. Returns null if
     * ALPN was not configured or the peer did not select a protocol.
     */
    val negotiatedProtocol: String?

    /**
     * The peer's certificate chain in DER encoding, or empty.
     *
     * Available after [isHandshakeComplete] becomes true. The first element
     * is the peer's end-entity certificate; subsequent elements are the chain.
     * Empty if the peer did not present a certificate.
     */
    val peerCertificates: List<ByteArray>

    /**
     * Sends close_notify and releases TLS-layer resources.
     *
     * After close, [protect] and [unprotect] must not be called.
     * Does NOT close the underlying transport.
     * Safe to call multiple times (idempotent).
     */
    fun close()
}
