package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.EmptyIoBuf
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import io.github.fukusaka.keel.pipeline.TypedInboundHandler
import kotlin.reflect.KClass

/**
 * Pipeline handler that decodes inbound [IoBuf] chunks into [HttpRequestHead] messages.
 *
 * Accepts raw bytes from the pipeline, scans for CRLF-terminated lines,
 * and emits one [HttpRequestHead] per complete HTTP/1.1 request head.
 * The body is not buffered — Content-Length bytes are skipped in-place
 * so the next pipelined request in the same [IoBuf] can be decoded
 * immediately.
 *
 * **State machine**:
 * ```
 * READ_REQUEST_LINE ──► READ_HEADERS ──► emit HttpRequestHead
 *       ▲                                      │
 *       │        (no body)                     ├─── Content-Length > 0 ──► READ_FIXED_BODY
 *       ├── emit HttpBodyEnd.EMPTY ◄───────────┤                              │
 *       │                                      └─── chunked ──► READ_CHUNK_SIZE
 *       │                                                            │
 *       ├── emit HttpBodyEnd ◄── READ_FIXED_BODY ──► emit HttpBody   │
 *       │                                                            │
 *       │     ┌── READ_CHUNK_DATA ◄── (size > 0) ◄─┤                │
 *       │     │         │                           │                │
 *       │     │    READ_CHUNK_DATA_CRLF ──► READ_CHUNK_SIZE ◄───────┘
 *       │     │
 *       └── emit HttpBodyEnd ◄── READ_CHUNK_TRAILER ◄── (size == 0)
 * ```
 *
 * **Byte-offset parsing**: each call to [onReadTyped] scans the current
 * [IoBuf] for LF via [IoBuf.getByte] and parses the matched line
 * directly from the buffer's byte range without allocating an
 * intermediate `StringBuilder` / `String` per character. Only the
 * stored fields ([uri], header name, header value) allocate a `String`,
 * and method / version lookups go through [HttpMethod.fromBytesOrNull]
 * / [HttpVersion.fromBytes] so that standard tokens such as `GET` and
 * `HTTP/1.1` return a cached constant without any allocation on the
 * success path.
 *
 * **Partial reads**: when a line spans more than one [IoBuf], the
 * trailing bytes of the current buffer are copied into a lazily
 * allocated byte accumulator, and the rest of the line from the next
 * buffer is appended before parsing. The accumulator is sized to
 * [MAX_LINE_SIZE] at most and its backing `ByteArray` is retained
 * across lines and even across parse errors within the same
 * connection, so that a decoder which once triggered a partial read
 * does not reallocate the accumulator on every subsequent line. The
 * handler is stateful and must not be shared between connections.
 *
 * **HTTP pipelining**: after a complete head is emitted, remaining bytes
 * in the same [IoBuf] are processed immediately, potentially emitting
 * multiple [HttpRequestHead] messages per invocation.
 *
 * **Body handling**: both Content-Length and chunked transfer-encoding
 * bodies are decoded into a sequence of [HttpBody] chunks terminated
 * by [HttpBodyEnd]. Every complete request produces exactly one
 * [HttpBodyEnd] — even requests with no body emit [HttpBodyEnd.EMPTY].
 * Chunked trailers are delivered via [HttpBodyEnd.trailers].
 *
 * **Error handling**: on [HttpParseException], the handler resets its
 * state and propagates the error downstream. The caller (typically the
 * application handler) is responsible for closing the connection.
 */
class HttpRequestDecoder : TypedInboundHandler<IoBuf>(IoBuf::class, autoRelease = false) {

    override val producedType: KClass<*> get() = HttpMessage::class

    private enum class State {
        READ_REQUEST_LINE,
        READ_HEADERS,
        READ_FIXED_BODY,
        READ_CHUNK_SIZE,
        READ_CHUNK_DATA,
        READ_CHUNK_DATA_CRLF,
        READ_CHUNK_TRAILER,
    }

    private var state = State.READ_REQUEST_LINE

    // Fallback accumulator — lazily allocated on the first cross-IoBuf line.
    // `null` in the steady state where every line fits in a single IoBuf.
    // The `ByteArray` itself is retained across requests (even across
    // connection errors) so that a connection that once triggered a
    // partial read does not keep reallocating; only `accumulatorSize` is
    // reset between lines.
    private var accumulator: ByteArray? = null
    private var accumulatorSize: Int = 0

    // Reusable scratch buffer for [bufRangeToString]. The fast path needs
    // to materialise a `String` for stored fields (URI, header name,
    // header value) from a byte range inside the current [IoBuf], and
    // there is no way to avoid copying the bytes into a `ByteArray`
    // before calling `decodeToString`. Instead of allocating a fresh
    // `ByteArray` on every call, we retain one `ByteArray` per decoder
    // instance and grow it on demand. This turns the per-call tmp
    // `ByteArray` into a single per-connection allocation that is
    // reused for every subsequent request.
    //
    // Size starts at [INITIAL_SCRATCH_CAPACITY]; doubles on demand up
    // to [MAX_LINE_SIZE] (same cap as the accumulator). The scratch
    // buffer is only valid for the duration of a single
    // `bufRangeToString` call — the returned `String` copies its
    // contents — so no lifecycle handling beyond growth is needed.
    private var scratchBuffer: ByteArray = ByteArray(INITIAL_SCRATCH_CAPACITY)

    private var method: HttpMethod? = null
    private var uri: String? = null
    private var version: HttpVersion? = null
    private var headers = HttpHeaders()
    private var bodyBytesRemaining: Long = 0L

    // Trailer accumulator for READ_CHUNK_TRAILER. Null until the first
    // trailer line is encountered; reset after emitting HttpBodyEnd.
    private var chunkTrailers: HttpHeaders? = null

    // CRLF consumption progress for READ_CHUNK_DATA_CRLF. Tracks how
    // many of the 2 expected bytes (CR, LF) have been consumed across
    // partial reads.
    private var chunkCrlfSeen: Int = 0

    override fun onReadTyped(ctx: PipelineHandlerContext, msg: IoBuf) {
        try {
            processBuffer(ctx, msg)
        } catch (e: HttpParseException) {
            resetState()
            ctx.propagateError(e)
        } finally {
            msg.release()
        }
    }

    private fun processBuffer(ctx: PipelineHandlerContext, buf: IoBuf) {
        while (buf.readableBytes > 0) {
            when (state) {
                State.READ_FIXED_BODY -> {
                    val avail = buf.readableBytes
                    if (avail == 0) return
                    val toEmit = minOf(bodyBytesRemaining, avail.toLong()).toInt()
                    val chunk = ctx.allocator.slice(buf, buf.readerIndex, toEmit)
                    buf.readerIndex += toEmit
                    bodyBytesRemaining -= toEmit
                    if (bodyBytesRemaining == 0L) {
                        ctx.propagateRead(HttpBodyEnd(chunk, HttpHeaders.EMPTY))
                        state = State.READ_REQUEST_LINE
                    } else {
                        ctx.propagateRead(HttpBody(chunk))
                    }
                }
                State.READ_CHUNK_DATA -> {
                    val avail = buf.readableBytes
                    if (avail == 0) return
                    val toEmit = minOf(bodyBytesRemaining, avail.toLong()).toInt()
                    val chunk = ctx.allocator.slice(buf, buf.readerIndex, toEmit)
                    buf.readerIndex += toEmit
                    bodyBytesRemaining -= toEmit
                    ctx.propagateRead(HttpBody(chunk))
                    if (bodyBytesRemaining == 0L) {
                        state = State.READ_CHUNK_DATA_CRLF
                        chunkCrlfSeen = 0
                    }
                }
                State.READ_CHUNK_DATA_CRLF -> {
                    if (!consumeChunkDataCrlf(buf)) return
                    state = State.READ_CHUNK_SIZE
                }
                State.READ_REQUEST_LINE, State.READ_HEADERS,
                State.READ_CHUNK_SIZE, State.READ_CHUNK_TRAILER,
                -> {
                    if (!processOneLine(ctx, buf)) return
                }
            }
        }
    }

    /**
     * Tries to parse exactly one line from [buf].
     *
     * Returns `true` when a line was consumed (fast or fallback path) —
     * the caller should then re-check [buf] for more bytes in the next
     * iteration of [processBuffer]. Returns `false` when [buf] did not
     * contain a line terminator; the remaining bytes (if any) have been
     * moved into [accumulator] and [buf] has been drained, so
     * [processBuffer] must return to wait for the next read.
     */
    private fun processOneLine(ctx: PipelineHandlerContext, buf: IoBuf): Boolean {
        val lfIndex = scanLf(buf, buf.readerIndex, buf.writerIndex)
        if (lfIndex < 0) {
            // No LF in this IoBuf — copy remainder to accumulator for the
            // next read. Enforces MAX_LINE_SIZE inside appendToAccumulator.
            val remaining = buf.writerIndex - buf.readerIndex
            if (remaining > 0) {
                appendToAccumulator(buf, buf.readerIndex, remaining)
                buf.readerIndex = buf.writerIndex
            }
            return false
        }

        if (accumulatorSize == 0) {
            // Fast path: the whole line is in this buffer.
            processLineFast(ctx, buf, lfIndex)
        } else {
            // Fallback path: earlier calls deposited the start of the line
            // in the accumulator; this call owns the tail.
            processLineFallback(ctx, buf, lfIndex)
        }
        return true
    }

    private fun processLineFast(ctx: PipelineHandlerContext, buf: IoBuf, lfIndex: Int) {
        val lineStart = buf.readerIndex
        var lineEnd = lfIndex
        if (lineEnd > lineStart && buf.getByte(lineEnd - 1) == CR) lineEnd--
        val lineLength = lineEnd - lineStart
        if (lineLength > MAX_LINE_SIZE) {
            throw HttpParseException(
                "Header line exceeds maximum length ($MAX_LINE_SIZE bytes)",
            )
        }
        buf.readerIndex = lfIndex + 1
        when (state) {
            State.READ_REQUEST_LINE -> {
                parseRequestLineFast(buf, lineStart, lineLength)
                state = State.READ_HEADERS
            }
            State.READ_HEADERS -> {
                if (lineLength == 0) {
                    emitHead(ctx)
                } else {
                    parseHeaderLineFast(buf, lineStart, lineLength)
                }
            }
            State.READ_CHUNK_SIZE -> {
                val size = parseChunkSizeFromBuf(buf, lineStart, lineLength)
                bodyBytesRemaining = size
                state = if (size == 0L) State.READ_CHUNK_TRAILER else State.READ_CHUNK_DATA
            }
            State.READ_CHUNK_TRAILER -> {
                if (lineLength == 0) {
                    emitLastWithTrailers(ctx)
                } else {
                    val trailers = chunkTrailers ?: HttpHeaders().also { chunkTrailers = it }
                    parseTrailerLineFast(buf, lineStart, lineLength, trailers)
                }
            }
            State.READ_FIXED_BODY, State.READ_CHUNK_DATA,
            State.READ_CHUNK_DATA_CRLF,
            -> Unit // unreachable — processBuffer routes these states elsewhere.
        }
    }

    private fun processLineFallback(ctx: PipelineHandlerContext, buf: IoBuf, lfIndex: Int) {
        val tailLength = lfIndex - buf.readerIndex
        if (tailLength > 0) {
            appendToAccumulator(buf, buf.readerIndex, tailLength)
        }
        buf.readerIndex = lfIndex + 1
        val arr = accumulator!!
        var effLength = accumulatorSize
        if (effLength > 0 && arr[effLength - 1] == CR) effLength--
        if (effLength > MAX_LINE_SIZE) {
            throw HttpParseException(
                "Header line exceeds maximum length ($MAX_LINE_SIZE bytes)",
            )
        }
        try {
            when (state) {
                State.READ_REQUEST_LINE -> {
                    parseRequestLineFallback(arr, 0, effLength)
                    state = State.READ_HEADERS
                }
                State.READ_HEADERS -> {
                    if (effLength == 0) {
                        emitHead(ctx)
                    } else {
                        parseHeaderLineFallback(arr, 0, effLength)
                    }
                }
                State.READ_CHUNK_SIZE -> {
                    val size = parseChunkSizeFromArr(arr, 0, effLength)
                    bodyBytesRemaining = size
                    state = if (size == 0L) State.READ_CHUNK_TRAILER else State.READ_CHUNK_DATA
                }
                State.READ_CHUNK_TRAILER -> {
                    if (effLength == 0) {
                        emitLastWithTrailers(ctx)
                    } else {
                        val trailers = chunkTrailers ?: HttpHeaders().also { chunkTrailers = it }
                        parseTrailerLineFallback(arr, 0, effLength, trailers)
                    }
                }
                State.READ_FIXED_BODY, State.READ_CHUNK_DATA,
                State.READ_CHUNK_DATA_CRLF,
                -> Unit // unreachable.
            }
        } finally {
            // Reset logical size so subsequent lines can reuse the ByteArray.
            accumulatorSize = 0
        }
    }

    // --- Accumulator management ---

    private fun appendToAccumulator(buf: IoBuf, offset: Int, length: Int) {
        if (length == 0) return
        val newSize = accumulatorSize + length
        if (newSize > MAX_LINE_SIZE) {
            throw HttpParseException(
                "Header line exceeds maximum length ($MAX_LINE_SIZE bytes)",
            )
        }
        ensureAccumulatorCapacity(newSize)
        val arr = accumulator!!
        for (i in 0 until length) {
            arr[accumulatorSize + i] = buf.getByte(offset + i)
        }
        accumulatorSize = newSize
    }

    private fun ensureAccumulatorCapacity(required: Int) {
        val cur = accumulator
        if (cur != null && cur.size >= required) return
        val newCap = if (cur == null) {
            maxOf(required, INITIAL_ACCUMULATOR_CAPACITY)
        } else {
            // Double, capped at MAX_LINE_SIZE so that the accumulator cannot
            // grow past the hard line-size limit.
            minOf(MAX_LINE_SIZE, maxOf(required, cur.size * 2))
        }
        val next = ByteArray(newCap)
        if (cur != null && accumulatorSize > 0) {
            cur.copyInto(next, 0, 0, accumulatorSize)
        }
        accumulator = next
    }

    // --- Line parsing (fast path, IoBuf-backed) ---

    private fun parseRequestLineFast(buf: IoBuf, start: Int, length: Int) {
        val end = start + length
        val sp1 = indexOfByteInBuf(buf, start, end, SP)
        if (sp1 <= start) throwInvalidRequestLineFromBuf(buf, start, length)
        val sp2 = indexOfByteInBuf(buf, sp1 + 1, end, SP)
        if (sp2 < 0) throwInvalidRequestLineFromBuf(buf, start, length)
        if (indexOfByteInBuf(buf, sp2 + 1, end, SP) >= 0) {
            throwInvalidRequestLineFromBuf(buf, start, length)
        }

        val methodLen = sp1 - start
        method = HttpMethod.fromBytesOrNull(buf, start, methodLen)
            ?: HttpMethod.of(bufRangeToString(buf, start, methodLen))

        val uriStart = sp1 + 1
        val uriLen = sp2 - uriStart
        uri = bufRangeToString(buf, uriStart, uriLen)

        val verStart = sp2 + 1
        val verLen = end - verStart
        version = HttpVersion.fromBytes(buf, verStart, verLen)
    }

    private fun parseHeaderLineFast(buf: IoBuf, start: Int, length: Int) {
        val first = buf.getByte(start)
        if (first == SP || first == HT) {
            throw HttpParseException(
                "Obsolete line folding (obs-fold) is not allowed (RFC 7230 §3.2.6)",
            )
        }
        val end = start + length
        val colon = indexOfByteInBuf(buf, start, end, COLON)
        // Name: [start, colon), trim OWS from the right (the obs-fold check
        // already rejected any leading OWS). Consolidating "colon missing"
        // and "empty name" into a single check keeps the throw count under
        // detekt's ThrowsCount limit.
        val nameEnd = if (colon > start) trimRightInBuf(buf, start, colon) else start
        val nameLen = nameEnd - start
        if (colon <= start || nameLen == 0) {
            throw HttpParseException(
                "Invalid header field (missing ':'): ${bufRangeToString(buf, start, length)}",
            )
        }
        val name = bufRangeToString(buf, start, nameLen)

        // Value: (colon, end), trim OWS from both sides.
        val valStart = trimLeftInBuf(buf, colon + 1, end)
        val valEnd = trimRightInBuf(buf, valStart, end)
        val value = bufRangeToString(buf, valStart, valEnd - valStart)

        headers.add(name, value)
    }

    private fun throwInvalidRequestLineFromBuf(buf: IoBuf, start: Int, length: Int): Nothing {
        throw HttpParseException(
            "Invalid request line (expected 3 tokens): ${bufRangeToString(buf, start, length)}",
        )
    }

    // --- Line parsing (fallback path, ByteArray-backed) ---

    private fun parseRequestLineFallback(arr: ByteArray, start: Int, length: Int) {
        val end = start + length
        val sp1 = indexOfByteInArr(arr, start, end, SP)
        if (sp1 <= start) throwInvalidRequestLineFromArr(arr, start, length)
        val sp2 = indexOfByteInArr(arr, sp1 + 1, end, SP)
        if (sp2 < 0) throwInvalidRequestLineFromArr(arr, start, length)
        if (indexOfByteInArr(arr, sp2 + 1, end, SP) >= 0) {
            throwInvalidRequestLineFromArr(arr, start, length)
        }

        val methodLen = sp1 - start
        method = HttpMethod.fromBytesOrNull(arr, start, methodLen)
            ?: HttpMethod.of(arr.decodeToString(start, start + methodLen))

        val uriStart = sp1 + 1
        val uriLen = sp2 - uriStart
        uri = arr.decodeToString(uriStart, uriStart + uriLen)

        val verStart = sp2 + 1
        val verLen = end - verStart
        version = HttpVersion.fromBytes(arr, verStart, verLen)
    }

    private fun parseHeaderLineFallback(arr: ByteArray, start: Int, length: Int) {
        val first = arr[start]
        if (first == SP || first == HT) {
            throw HttpParseException(
                "Obsolete line folding (obs-fold) is not allowed (RFC 7230 §3.2.6)",
            )
        }
        val end = start + length
        val colon = indexOfByteInArr(arr, start, end, COLON)
        // Consolidated "colon missing" and "empty name" check — see the
        // fast-path variant above for the rationale.
        val nameEnd = if (colon > start) trimRightInArr(arr, start, colon) else start
        val nameLen = nameEnd - start
        if (colon <= start || nameLen == 0) {
            throw HttpParseException(
                "Invalid header field (missing ':'): ${arr.decodeToString(start, end)}",
            )
        }
        val name = arr.decodeToString(start, nameEnd)

        val valStart = trimLeftInArr(arr, colon + 1, end)
        val valEnd = trimRightInArr(arr, valStart, end)
        val value = arr.decodeToString(valStart, valEnd)

        headers.add(name, value)
    }

    private fun throwInvalidRequestLineFromArr(arr: ByteArray, start: Int, length: Int): Nothing {
        throw HttpParseException(
            "Invalid request line (expected 3 tokens): ${arr.decodeToString(start, start + length)}",
        )
    }

    // --- Byte-level primitives ---

    private fun scanLf(buf: IoBuf, from: Int, until: Int): Int {
        for (i in from until until) {
            if (buf.getByte(i) == LF) return i
        }
        return -1
    }

    private fun indexOfByteInBuf(buf: IoBuf, from: Int, until: Int, b: Byte): Int {
        for (i in from until until) {
            if (buf.getByte(i) == b) return i
        }
        return -1
    }

    private fun trimLeftInBuf(buf: IoBuf, from: Int, until: Int): Int {
        var i = from
        while (i < until) {
            val b = buf.getByte(i)
            if (b != SP && b != HT) break
            i++
        }
        return i
    }

    private fun trimRightInBuf(buf: IoBuf, from: Int, until: Int): Int {
        var i = until
        while (i > from) {
            val b = buf.getByte(i - 1)
            if (b != SP && b != HT) break
            i--
        }
        return i
    }

    private fun bufRangeToString(buf: IoBuf, offset: Int, length: Int): String {
        val scratch = ensureScratchCapacity(length)
        for (i in 0 until length) scratch[i] = buf.getByte(offset + i)
        return scratch.decodeToString(0, length)
    }

    private fun ensureScratchCapacity(required: Int): ByteArray {
        val cur = scratchBuffer
        if (cur.size >= required) return cur
        // Double on demand, capped at MAX_LINE_SIZE (the same bound the
        // fast path enforces on `lineLength`, so scratch never needs to
        // hold more than that).
        val newCap = minOf(MAX_LINE_SIZE, maxOf(required, cur.size * 2))
        val next = ByteArray(newCap)
        scratchBuffer = next
        return next
    }

    private fun indexOfByteInArr(arr: ByteArray, from: Int, until: Int, b: Byte): Int {
        for (i in from until until) {
            if (arr[i] == b) return i
        }
        return -1
    }

    private fun trimLeftInArr(arr: ByteArray, from: Int, until: Int): Int {
        var i = from
        while (i < until) {
            val b = arr[i]
            if (b != SP && b != HT) break
            i++
        }
        return i
    }

    private fun trimRightInArr(arr: ByteArray, from: Int, until: Int): Int {
        var i = until
        while (i > from) {
            val b = arr[i - 1]
            if (b != SP && b != HT) break
            i--
        }
        return i
    }

    // --- Chunked transfer-encoding helpers ---

    /**
     * Consumes the CRLF terminator after chunk-data. Returns `true` when
     * both bytes have been consumed, `false` if more data is needed.
     */
    private fun consumeChunkDataCrlf(buf: IoBuf): Boolean {
        while (buf.readableBytes > 0 && chunkCrlfSeen < CRLF_LENGTH) {
            val b = buf.getByte(buf.readerIndex)
            buf.readerIndex += 1
            val expected = if (chunkCrlfSeen == 0) CR else LF
            if (b != expected) {
                throw HttpParseException(
                    "Chunk-data missing terminating CRLF (RFC 7230 §4.1.1)",
                )
            }
            chunkCrlfSeen++
        }
        return chunkCrlfSeen == CRLF_LENGTH
    }

    /**
     * Parses a chunk-size line from the fast-path IoBuf. The format is:
     * `HEX *WSP [";" chunk-ext] CRLF` (RFC 7230 §4.1.1). Chunk extensions
     * are accepted but discarded.
     */
    private fun parseChunkSizeFromBuf(buf: IoBuf, start: Int, length: Int): Long {
        val end = start + length
        val extStart = indexOfByteInBuf(buf, start, end, SEMICOLON)
        val sizeEnd = if (extStart >= 0) extStart else end
        val trimmed = trimRightInBuf(buf, start, sizeEnd)
        return parseHexFromBuf(buf, start, trimmed - start, length)
    }

    private fun parseHexFromBuf(buf: IoBuf, start: Int, hexLen: Int, lineLen: Int): Long {
        if (hexLen == 0 || hexLen > MAX_CHUNK_SIZE_HEX_DIGITS) {
            throwInvalidChunkSizeFromBuf(buf, start, lineLen)
        }
        var value = 0L
        for (i in 0 until hexLen) {
            val digit = hexDigit(buf.getByte(start + i).toInt() and 0xFF)
            if (digit < 0) throwInvalidChunkSizeFromBuf(buf, start, lineLen)
            value = (value shl 4) or digit.toLong()
        }
        if (value < 0L) throwInvalidChunkSizeFromBuf(buf, start, lineLen)
        return value
    }

    private fun throwInvalidChunkSizeFromBuf(buf: IoBuf, start: Int, lineLen: Int): Nothing {
        throw HttpParseException(
            "Invalid chunk size: ${bufRangeToString(buf, start, lineLen)}",
        )
    }

    /** Parses a chunk-size line from the fallback-path ByteArray. */
    private fun parseChunkSizeFromArr(arr: ByteArray, start: Int, length: Int): Long {
        val end = start + length
        val extStart = indexOfByteInArr(arr, start, end, SEMICOLON)
        val sizeEnd = if (extStart >= 0) extStart else end
        val trimmed = trimRightInArr(arr, start, sizeEnd)
        return parseHexFromArr(arr, start, trimmed - start, length)
    }

    private fun parseHexFromArr(arr: ByteArray, start: Int, hexLen: Int, lineLen: Int): Long {
        if (hexLen == 0 || hexLen > MAX_CHUNK_SIZE_HEX_DIGITS) {
            throwInvalidChunkSizeFromArr(arr, start, lineLen)
        }
        var value = 0L
        for (i in 0 until hexLen) {
            val digit = hexDigit(arr[start + i].toInt() and 0xFF)
            if (digit < 0) throwInvalidChunkSizeFromArr(arr, start, lineLen)
            value = (value shl 4) or digit.toLong()
        }
        if (value < 0L) throwInvalidChunkSizeFromArr(arr, start, lineLen)
        return value
    }

    private fun throwInvalidChunkSizeFromArr(arr: ByteArray, start: Int, lineLen: Int): Nothing {
        throw HttpParseException(
            "Invalid chunk size: ${arr.decodeToString(start, start + lineLen)}",
        )
    }

    private fun hexDigit(b: Int): Int = when {
        b in '0'.code..'9'.code -> b - '0'.code
        b in 'a'.code..'f'.code -> b - 'a'.code + 10
        b in 'A'.code..'F'.code -> b - 'A'.code + 10
        else -> -1
    }

    /**
     * Parses a trailer header line from the fast-path IoBuf into [trailers].
     * Same shape as [parseHeaderLineFast] but writes into the provided
     * [HttpHeaders] instance instead of the head-level [headers] field.
     */
    private fun parseTrailerLineFast(buf: IoBuf, start: Int, length: Int, trailers: HttpHeaders) {
        val first = buf.getByte(start)
        if (first == SP || first == HT) {
            throw HttpParseException(
                "Obsolete line folding (obs-fold) is not allowed in trailers (RFC 7230 §3.2.6)",
            )
        }
        val end = start + length
        val colon = indexOfByteInBuf(buf, start, end, COLON)
        val nameEnd = if (colon > start) trimRightInBuf(buf, start, colon) else start
        val nameLen = nameEnd - start
        if (colon <= start || nameLen == 0) {
            throw HttpParseException(
                "Invalid trailer field (missing ':'): ${bufRangeToString(buf, start, length)}",
            )
        }
        val name = bufRangeToString(buf, start, nameLen)
        val valStart = trimLeftInBuf(buf, colon + 1, end)
        val valEnd = trimRightInBuf(buf, valStart, end)
        val value = bufRangeToString(buf, valStart, valEnd - valStart)
        trailers.add(name, value)
    }

    /** Parses a trailer header line from the fallback-path ByteArray. */
    private fun parseTrailerLineFallback(arr: ByteArray, start: Int, length: Int, trailers: HttpHeaders) {
        val first = arr[start]
        if (first == SP || first == HT) {
            throw HttpParseException(
                "Obsolete line folding (obs-fold) is not allowed in trailers (RFC 7230 §3.2.6)",
            )
        }
        val end = start + length
        val colon = indexOfByteInArr(arr, start, end, COLON)
        val nameEnd = if (colon > start) trimRightInArr(arr, start, colon) else start
        val nameLen = nameEnd - start
        if (colon <= start || nameLen == 0) {
            throw HttpParseException(
                "Invalid trailer field (missing ':'): ${arr.decodeToString(start, end)}",
            )
        }
        val name = arr.decodeToString(start, nameEnd)
        val valStart = trimLeftInArr(arr, colon + 1, end)
        val valEnd = trimRightInArr(arr, valStart, end)
        val value = arr.decodeToString(valStart, valEnd)
        trailers.add(name, value)
    }

    private fun emitLastWithTrailers(ctx: PipelineHandlerContext) {
        val trailers = chunkTrailers
        chunkTrailers = null
        val last = if (trailers == null || trailers.isEmpty) {
            HttpBodyEnd.EMPTY
        } else {
            HttpBodyEnd(EmptyIoBuf, trailers)
        }
        ctx.propagateRead(last)
        state = State.READ_REQUEST_LINE
    }

    // --- Emit / reset ---

    private fun emitHead(ctx: PipelineHandlerContext) {
        val parsedVersion = checkNotNull(version) { "version not parsed" }
        // RFC 7230 §5.4: Host header is mandatory for HTTP/1.1 requests.
        if (parsedVersion == HttpVersion.HTTP_1_1 && HttpHeaderName.HOST !in headers) {
            throw HttpParseException("Missing required Host header (RFC 7230 §5.4)")
        }
        // RFC 7230 §3.3.3: reject requests with both Content-Length and Transfer-Encoding
        // to prevent HTTP Request Smuggling.
        if (headers.isChunked && headers.contentLength != null) {
            throw HttpParseException(
                "Both Transfer-Encoding and Content-Length present (RFC 7230 §3.3.3)",
            )
        }
        val head = HttpRequestHead(
            checkNotNull(method) { "method not parsed" },
            checkNotNull(uri) { "uri not parsed" },
            parsedVersion,
            headers,
        )
        // Reset parser state before emitting to allow re-entrant pipeline processing.
        method = null
        uri = null
        version = null
        headers = HttpHeaders()
        ctx.propagateRead(head)

        val cl = head.headers.contentLength
        when {
            head.headers.isChunked -> {
                state = State.READ_CHUNK_SIZE
                bodyBytesRemaining = 0L
            }
            cl != null && cl > 0L -> {
                bodyBytesRemaining = cl
                state = State.READ_FIXED_BODY
            }
            else -> {
                // No body — emit empty terminator so downstream handlers
                // can rely on "every request ends with an HttpBodyEnd".
                ctx.propagateRead(HttpBodyEnd.EMPTY)
                state = State.READ_REQUEST_LINE
            }
        }
    }

    private fun resetState() {
        state = State.READ_REQUEST_LINE
        accumulatorSize = 0
        method = null
        uri = null
        version = null
        headers = HttpHeaders()
        bodyBytesRemaining = 0L
        chunkTrailers = null
        chunkCrlfSeen = 0
    }

    private companion object {
        /** Maximum allowed length for a single header line (request line or header field). */
        private const val MAX_LINE_SIZE = 8192

        /**
         * Initial capacity of the fallback byte accumulator, in bytes. Typical
         * HTTP request heads (request line + a handful of headers) fit within
         * this size, so the accumulator usually does not need to grow.
         */
        private const val INITIAL_ACCUMULATOR_CAPACITY = 256

        /**
         * Initial capacity of the per-decoder scratch buffer used by
         * [bufRangeToString] to copy bytes out of an [IoBuf] before calling
         * [ByteArray.decodeToString]. Chosen to fit a typical HTTP request
         * URI and header value without growth; grows on demand up to
         * [MAX_LINE_SIZE].
         */
        private const val INITIAL_SCRATCH_CAPACITY = 256

        private val LF = '\n'.code.toByte()
        private val CR = '\r'.code.toByte()
        private val SP = ' '.code.toByte()
        private val HT = '\t'.code.toByte()
        private val COLON = ':'.code.toByte()
        private val SEMICOLON = ';'.code.toByte()

        /** Maximum hex digits for a chunk size (16 hex digits = 2^64). */
        private const val MAX_CHUNK_SIZE_HEX_DIGITS = 16

        private const val CRLF_LENGTH = 2
    }
}
