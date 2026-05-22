package io.github.fukusaka.keel.buf

/**
 * Decodes the byte range `[start, start + length)` of [buf] into a [String]
 * using **ISO-8859-1 (Latin-1)** semantics — each byte 0x00–0xFF becomes the
 * [Char] of the same codepoint (RFC 7230 §3.2.6 obs-text is opaque, lossless
 * / reversible). The materialisation primitive behind [IoBufAsciiText.toString]
 * and the HTTP header / trailer field decode.
 *
 * Implementations use the **platform-optimal bulk path** rather than a
 * per-byte [IoBuf.getByte] loop: a single bulk copy out of the backing
 * (JVM `ByteBuffer.get(index, dst, …)` absolute bulk read + the JDK
 * compact-string Latin-1 constructor; Native a `CPointer` read; JS a per-byte
 * read). On JVM this also avoids the intermediate `CharArray` that
 * `concatToString` needs, so a framework that materialises every header to
 * `String` (e.g. the Ktor adapter) allocates less per request.
 *
 * @param buf    backing buffer (must outlive this call).
 * @param start  absolute start byte index (inclusive).
 * @param length number of bytes to decode.
 */
public expect fun ioBufToLatin1String(buf: IoBuf, start: Int, length: Int): String
