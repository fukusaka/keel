package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.EmptyIoBuf
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.ioBufToLatin1String
import io.github.fukusaka.keel.pipeline.DuplexHandler
import io.github.fukusaka.keel.pipeline.PipelineHandlerContext
import kotlin.reflect.KClass

/**
 * Pipeline handler that decodes inbound [IoBuf] chunks into streaming
 * [HttpResponseHead] + [HttpBody] / [HttpBodyEnd] messages — the client
 * counterpart of [HttpRequestDecoder].
 *
 * **State machine**:
 * ```
 * READ_STATUS_LINE ──► READ_HEADERS ──► emit HttpResponseHead
 *       ▲                                      │
 *       │   (bodyless: HEAD / 1xx / 204 / 304 / CL 0)
 *       ├── emit HttpBodyEnd.EMPTY ◄───────────┤
 *       │                                      ├─── Content-Length > 0 ──► READ_FIXED_BODY
 *       ├── emit HttpBodyEnd ◄── READ_FIXED_BODY ──► emit HttpBody
 *       │                                      ├─── chunked ──► READ_CHUNK_SIZE / DATA / TRAILER
 *       ├── emit HttpBodyEnd ◄── READ_CHUNK_TRAILER
 *       │                                      ├─── neither ──► READ_UNTIL_CLOSE ── EOF ─► HttpBodyEnd
 *       └──────────────────────────────────────┴─── 101 / CONNECT 2xx ──► PASS_THROUGH
 * ```
 *
 * **Response body framing** (RFC 9112 §6.3): a response to a `HEAD`
 * request, any 1xx / 204 / 304 status, and a 2xx response to `CONNECT`
 * carry no body regardless of their framing headers. Otherwise
 * `Transfer-Encoding: chunked` selects chunked decoding, `Content-Length`
 * a fixed-size body, and a response with neither is delimited by
 * connection close ([onInactive] then terminates it with an empty
 * [HttpBodyEnd]). A response carrying both `Content-Length` and
 * `Transfer-Encoding` is rejected with [HttpParseException], matching
 * [HttpRequestDecoder]'s request-smuggling stance.
 *
 * **Request-method context**: whether a response answers a `HEAD` or
 * `CONNECT` request cannot be derived from the response bytes. The decoder
 * implements [DuplexHandler] and snoops outbound [HttpRequestHead] /
 * [HttpRequest] messages to queue their methods — install it so outbound
 * request messages traverse it *before* they reach [HttpRequestEncoder]
 * (see `addHttp1ClientCodec`, which places the encoder closer to HEAD).
 * When the queue is empty (raw-byte writers, standalone use), responses
 * are framed as if the request were a regular non-HEAD method.
 *
 * **Interim responses**: a non-101 1xx (e.g. `100 Continue`) is emitted as
 * a bodyless head + [HttpBodyEnd.EMPTY] and decoding continues with the
 * *same* queued request method, so the final response still sees it.
 * `101 Switching Protocols` and a 2xx to `CONNECT` switch the connection
 * away from HTTP: the decoder emits the bodyless head and then enters
 * PASS_THROUGH, forwarding all remaining bytes downstream as raw [IoBuf]s
 * for the new protocol's handlers (the upgrade code typically removes this
 * codec from the pipeline).
 *
 * **Header materialisation**: unlike [HttpRequestDecoder]'s zero-copy
 * byte-range views, header names and values are materialised as `String`s
 * (ISO-8859-1 byte-as-char, lossless for RFC 9110 obs-text) into a plain
 * [HttpHeaders]. Client consumers therefore inherit no
 * `headers.release()` obligation and no recv-buffer lifetime coupling.
 * Deliberate trade-off: the client path is not the server hot path;
 * revisit with the server decoder's range-view scheme only if profiling
 * shows the per-header `String`s matter.
 *
 * **Limits**: [headerLimits] caps the line size, header-field count, and
 * cumulative header bytes exactly like the server decoder — a malicious
 * or broken server must not be able to balloon client memory. Trailer
 * fields count against the same caps as the header block.
 *
 * **Error handling**: on [HttpParseException], the handler resets its
 * state and propagates the error downstream; the caller closes the
 * connection. A connection that closes mid-response (mid-head, short
 * fixed body, unterminated chunk) propagates [HttpEofException] before
 * the inactive event so consumers can distinguish truncation from a
 * cleanly delimited close.
 *
 * The handler is stateful and must not be shared between connections.
 */
class HttpResponseDecoder(
    private val headerLimits: HttpHeaderLimitsConfig = HttpHeaderLimitsConfig.DEFAULT,
) : DuplexHandler {

    override val acceptedType: KClass<*> get() = IoBuf::class
    override val producedType: KClass<*> get() = HttpMessage::class

    private enum class State {
        READ_STATUS_LINE,
        READ_HEADERS,
        READ_FIXED_BODY,
        READ_CHUNK_SIZE,
        READ_CHUNK_DATA,
        READ_CHUNK_DATA_CRLF,
        READ_CHUNK_TRAILER,
        READ_UNTIL_CLOSE,
        PASS_THROUGH,
    }

    private var state = State.READ_STATUS_LINE

    // Fallback accumulator for lines that straddle IoBuf boundaries.
    // Lazily allocated on the first straddle; the ByteArray is retained
    // across lines so a connection that once straddled does not
    // reallocate per line. Only `accumulatorSize` resets between lines.
    private var accumulator: ByteArray? = null
    private var accumulatorSize: Int = 0

    // Head fields of the response currently being parsed.
    private var status: HttpStatus? = null
    private var version: HttpVersion? = null
    private var headers = HttpHeaders()

    /**
     * Cumulative `(nameLen + valueLen)` bytes of every header and trailer
     * field of the in-progress response. Reset at the next status line —
     * not at head emission — so trailer fields count against the same
     * cumulative cap as the header block (same anti-flood shape as
     * [HttpRequestDecoder]).
     */
    private var headerByteCount: Int = 0

    private var bodyBytesRemaining: Long = 0L

    // Trailer accumulator for READ_CHUNK_TRAILER; null until the first
    // trailer line, reset after emitting HttpBodyEnd.
    private var chunkTrailers: HttpHeaders? = null

    // CRLF consumption progress for READ_CHUNK_DATA_CRLF.
    private var chunkCrlfSeen: Int = 0

    // Methods of requests written on this connection, in order. Pushed by
    // the outbound snoop in [onWrite]; popped when the matching final
    // response head is classified (interim 1xx responses peek without
    // popping). Empty queue = no method context = non-HEAD framing.
    private val pendingMethods = ArrayDeque<HttpMethod>()

    // --- Outbound: request-method snoop ---

    override fun onWrite(ctx: PipelineHandlerContext, msg: Any) {
        when (msg) {
            is HttpRequestHead -> pendingMethods.addLast(msg.method)
            is HttpRequest -> pendingMethods.addLast(msg.method)
            else -> Unit
        }
        ctx.propagateWrite(msg)
    }

    // --- Inbound: decode ---

    override fun onRead(ctx: PipelineHandlerContext, msg: Any) {
        if (msg !is IoBuf) {
            ctx.propagateRead(msg)
            return
        }
        if (state == State.PASS_THROUGH) {
            // Tunnel / upgraded protocol: forward raw bytes, ownership
            // transfers to the downstream consumer.
            ctx.propagateRead(msg)
            return
        }
        try {
            processBuffer(ctx, msg)
        } catch (e: HttpParseException) {
            resetState()
            ctx.propagateError(e)
        } finally {
            msg.release()
        }
    }

    override fun onInactive(ctx: PipelineHandlerContext) {
        when (state) {
            State.READ_UNTIL_CLOSE -> {
                // Close IS the delimiter (RFC 9112 §6.3 case 8): terminate
                // the streamed body cleanly before announcing inactivity.
                state = State.READ_STATUS_LINE
                ctx.propagateRead(HttpBodyEnd.EMPTY)
            }
            State.READ_STATUS_LINE -> {
                if (accumulatorSize > 0) {
                    propagateTruncation(ctx, "status line")
                }
            }
            State.READ_HEADERS -> propagateTruncation(ctx, "header block")
            State.READ_FIXED_BODY -> propagateTruncation(
                ctx,
                "fixed body ($bodyBytesRemaining bytes missing)",
            )
            State.READ_CHUNK_SIZE, State.READ_CHUNK_DATA,
            State.READ_CHUNK_DATA_CRLF, State.READ_CHUNK_TRAILER,
            -> propagateTruncation(ctx, "chunked body")
            State.PASS_THROUGH -> Unit // tunnel teardown is the new protocol's concern
        }
        ctx.propagateInactive()
    }

    private fun propagateTruncation(ctx: PipelineHandlerContext, where: String) {
        resetState()
        ctx.propagateError(
            HttpEofException("Connection closed mid-response while reading $where"),
        )
    }

    private fun processBuffer(ctx: PipelineHandlerContext, buf: IoBuf) {
        while (buf.readableBytes > 0) {
            when (state) {
                State.READ_FIXED_BODY -> {
                    val toEmit = minOf(bodyBytesRemaining, buf.readableBytes.toLong()).toInt()
                    val chunk = ctx.allocator.slice(buf, buf.readerIndex, toEmit)
                    buf.readerIndex += toEmit
                    bodyBytesRemaining -= toEmit
                    if (bodyBytesRemaining == 0L) {
                        state = State.READ_STATUS_LINE
                        ctx.propagateRead(HttpBodyEnd(chunk, HttpHeaders.EMPTY))
                    } else {
                        ctx.propagateRead(HttpBody(chunk))
                    }
                }
                State.READ_CHUNK_DATA -> {
                    val toEmit = minOf(bodyBytesRemaining, buf.readableBytes.toLong()).toInt()
                    val chunk = ctx.allocator.slice(buf, buf.readerIndex, toEmit)
                    buf.readerIndex += toEmit
                    bodyBytesRemaining -= toEmit
                    if (bodyBytesRemaining == 0L) {
                        state = State.READ_CHUNK_DATA_CRLF
                        chunkCrlfSeen = 0
                    }
                    ctx.propagateRead(HttpBody(chunk))
                }
                State.READ_CHUNK_DATA_CRLF -> {
                    if (!consumeChunkDataCrlf(buf)) return
                    state = State.READ_CHUNK_SIZE
                }
                State.READ_UNTIL_CLOSE -> {
                    val chunk = ctx.allocator.slice(buf, buf.readerIndex, buf.readableBytes)
                    buf.readerIndex = buf.writerIndex
                    ctx.propagateRead(HttpBody(chunk))
                }
                State.PASS_THROUGH -> {
                    // Bytes following a 101 / CONNECT-2xx head in the same
                    // buffer belong to the switched protocol — forward them
                    // untouched as an owned slice.
                    val rest = ctx.allocator.slice(buf, buf.readerIndex, buf.readableBytes)
                    buf.readerIndex = buf.writerIndex
                    ctx.propagateRead(rest)
                }
                State.READ_STATUS_LINE, State.READ_HEADERS,
                State.READ_CHUNK_SIZE, State.READ_CHUNK_TRAILER,
                -> {
                    if (!processOneLine(ctx, buf)) return
                }
            }
        }
    }

    // --- Line extraction ---

    /**
     * Tries to extract and parse exactly one CRLF-terminated line from
     * [buf]. Returns `true` when a line was consumed; `false` when [buf]
     * held no LF — its remaining bytes have moved into [accumulator] and
     * the caller must wait for the next read.
     */
    private fun processOneLine(ctx: PipelineHandlerContext, buf: IoBuf): Boolean {
        val lfIndex = scanLf(buf, buf.readerIndex, buf.writerIndex)
        if (lfIndex < 0) {
            val remaining = buf.writerIndex - buf.readerIndex
            if (remaining > 0) {
                appendToAccumulator(buf, remaining)
            }
            return false
        }
        val line = materialiseLine(buf, lfIndex)
        buf.readerIndex = lfIndex + 1
        parseLine(ctx, line)
        return true
    }

    /**
     * Materialises the line ending (exclusive) at [lfIndex] as an
     * ISO-8859-1 `String`, joining any bytes deposited in [accumulator] by
     * earlier partial reads, and stripping the optional trailing CR.
     *
     * Line materialisation is the client decoder's deliberate divergence
     * from [HttpRequestDecoder]: one `String` per line (then substring
     * fields) instead of dual IoBuf/ByteArray parse paths — see the class
     * KDoc.
     */
    private fun materialiseLine(buf: IoBuf, lfIndex: Int): String {
        val tailLength = lfIndex - buf.readerIndex
        if (accumulatorSize == 0) {
            var length = tailLength
            if (length > 0 && buf.getByte(buf.readerIndex + length - 1) == CR) length--
            enforceLineSizeCap(length)
            // Bulk platform decode straight off the buffer range — no
            // intermediate scratch copy (keel-io primitive, same one the
            // server decoder uses for its materialisation paths).
            return ioBufToLatin1String(buf, buf.readerIndex, length)
        }
        if (tailLength > 0) {
            appendToAccumulator(buf, tailLength)
        }
        val arr = accumulator!!
        var length = accumulatorSize
        accumulatorSize = 0
        if (length > 0 && arr[length - 1] == CR) length--
        enforceLineSizeCap(length)
        return latin1ToString(arr, length)
    }

    /**
     * ISO-8859-1 (byte-as-char) decode — lossless for obs-text 0x80-0xFF
     * header bytes (RFC 9110 §5.5), matching the server decoder's header
     * materialisation. A UTF-8 decode would corrupt lone high bytes to
     * U+FFFD.
     */
    private fun latin1ToString(arr: ByteArray, length: Int): String {
        if (length == 0) return ""
        val chars = CharArray(length)
        for (i in 0 until length) chars[i] = (arr[i].toInt() and 0xFF).toChar()
        return chars.concatToString()
    }

    /**
     * Appends the next [length] readable bytes of [buf] to the
     * accumulator with one bulk [IoBuf.readByteArray] (which advances the
     * buffer's `readerIndex`).
     *
     * The size cap allows one byte beyond `maxLineSize` so that a line of
     * exactly the cap whose CRLF straddles the read boundary (CR arrives,
     * LF does not) is not rejected before [materialiseLine] strips the CR
     * — the definitive post-strip cap check happens there. The
     * accumulator is therefore bounded at `maxLineSize + 1` bytes.
     */
    private fun appendToAccumulator(buf: IoBuf, length: Int) {
        if (length == 0) return
        val newSize = accumulatorSize + length
        if (newSize > headerLimits.maxLineSize + 1) {
            enforceLineSizeCap(newSize)
        }
        ensureAccumulatorCapacity(newSize)
        buf.readByteArray(accumulator!!, accumulatorSize, length)
        accumulatorSize = newSize
    }

    private fun ensureAccumulatorCapacity(required: Int) {
        val cur = accumulator
        if (cur != null && cur.size >= required) return
        val newCap = if (cur == null) {
            maxOf(required, INITIAL_ACCUMULATOR_CAPACITY)
        } else {
            // Double, capped at the accumulator bound (maxLineSize + the
            // one-byte CR allowance documented on [appendToAccumulator]).
            minOf(headerLimits.maxLineSize + 1, maxOf(required, cur.size * 2))
        }
        val next = ByteArray(newCap)
        if (cur != null && accumulatorSize > 0) {
            cur.copyInto(next, 0, 0, accumulatorSize)
        }
        accumulator = next
    }

    private fun scanLf(buf: IoBuf, from: Int, until: Int): Int {
        for (i in from until until) {
            if (buf.getByte(i) == LF) return i
        }
        return -1
    }

    // --- Line parsing ---

    private fun parseLine(ctx: PipelineHandlerContext, line: String) {
        when (state) {
            State.READ_STATUS_LINE -> {
                parseStatusLine(line)
                state = State.READ_HEADERS
            }
            State.READ_HEADERS -> {
                if (line.isEmpty()) {
                    emitHead(ctx)
                } else {
                    parseFieldLine(line, headers)
                }
            }
            State.READ_CHUNK_SIZE -> {
                val size = parseChunkSize(line)
                bodyBytesRemaining = size
                state = if (size == 0L) State.READ_CHUNK_TRAILER else State.READ_CHUNK_DATA
            }
            State.READ_CHUNK_TRAILER -> {
                if (line.isEmpty()) {
                    emitLastWithTrailers(ctx)
                } else {
                    val trailers = chunkTrailers ?: HttpHeaders().also { chunkTrailers = it }
                    parseFieldLine(line, trailers)
                }
            }
            State.READ_FIXED_BODY, State.READ_CHUNK_DATA, State.READ_CHUNK_DATA_CRLF,
            State.READ_UNTIL_CLOSE, State.PASS_THROUGH,
            -> Unit // unreachable — processBuffer routes these states elsewhere.
        }
    }

    /**
     * Parses `HTTP-version SP status-code SP [reason-phrase]` (RFC 9112
     * §4). The reason phrase is informational and discarded; a status
     * line without the reason segment is tolerated (matching
     * `parseStatusLine` in the Source-based parser).
     */
    private fun parseStatusLine(line: String) {
        // New response — the cumulative header/trailer byte counter of the
        // previous response ends here (see [headerByteCount]).
        headerByteCount = 0
        val sp1 = line.indexOf(' ')
        if (sp1 < 1) throw HttpParseException("Invalid status line (expected 3 tokens): $line")
        val sp2 = line.indexOf(' ', sp1 + 1)
        val codeEnd = if (sp2 >= 0) sp2 else line.length
        val code = line.substring(sp1 + 1, codeEnd).toIntOrNull()?.takeIf { it in 100..999 }
            ?: throw HttpParseException("Invalid status code in status line: $line")
        version = HttpVersion.of(line.substring(0, sp1))
        status = HttpStatus(code)
    }

    /**
     * Parses one `field-name ":" OWS field-value OWS` line into [target]
     * (the header block or a chunked-trailer block) and enforces the
     * count / cumulative-bytes caps.
     */
    private fun parseFieldLine(line: String, target: HttpHeaders) {
        val first = line[0]
        if (first == ' ' || first == '\t') {
            throw HttpParseException(
                "Obsolete line folding (obs-fold) is not allowed (RFC 7230 §3.2.6)",
            )
        }
        val colon = line.indexOf(':')
        val nameEnd = if (colon > 0) trimEndIndex(line, 0, colon) else 0
        if (colon < 1 || nameEnd == 0) {
            throw HttpParseException("Invalid header field (missing ':'): $line")
        }
        val name = line.substring(0, nameEnd)
        val valStart = trimStartIndex(line, colon + 1, line.length)
        val valEnd = trimEndIndex(line, valStart, line.length)
        val value = line.substring(valStart, valEnd)
        target.add(name, value)
        enforceHeaderCountCap(target.size)
        enforceHeaderBytesCap(name.length + value.length)
    }

    private fun trimStartIndex(s: String, from: Int, until: Int): Int {
        var i = from
        while (i < until && (s[i] == ' ' || s[i] == '\t')) i++
        return i
    }

    private fun trimEndIndex(s: String, from: Int, until: Int): Int {
        var i = until
        while (i > from && (s[i - 1] == ' ' || s[i - 1] == '\t')) i--
        return i
    }

    /**
     * Parses a chunk-size line: `HEX *WSP [";" chunk-ext]` (RFC 9112
     * §7.1). Extensions are accepted but discarded. Strict unsigned hex —
     * a sign prefix or more than 16 digits is rejected.
     */
    private fun parseChunkSize(line: String): Long {
        val sizeEnd = trimEndIndex(line, 0, line.indexOf(';').let { if (it >= 0) it else line.length })
        if (sizeEnd == 0 || sizeEnd > MAX_CHUNK_SIZE_HEX_DIGITS) {
            throwInvalidChunkSize(line)
        }
        var value = 0L
        for (i in 0 until sizeEnd) {
            val digit = hexDigit(line[i])
            if (digit < 0) throwInvalidChunkSize(line)
            value = (value shl 4) or digit.toLong()
        }
        if (value < 0L) throwInvalidChunkSize(line)
        return value
    }

    private fun throwInvalidChunkSize(line: String): Nothing {
        throw HttpParseException("Invalid chunk size: $line")
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

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

    // --- Limits ---

    private fun enforceLineSizeCap(actualLength: Int) {
        val cap = headerLimits.maxLineSize
        if (actualLength > cap) {
            throw HttpHeaderLimitExceededException(
                limitName = "maxLineSize",
                actual = actualLength,
                limit = cap,
            )
        }
    }

    private fun enforceHeaderCountCap(actualCount: Int) {
        val cap = headerLimits.maxHeaderCount
        if (actualCount > cap) {
            throw HttpHeaderLimitExceededException(
                limitName = "maxHeaderCount",
                actual = actualCount,
                limit = cap,
            )
        }
    }

    private fun enforceHeaderBytesCap(addedBytes: Int) {
        headerByteCount += addedBytes
        val cap = headerLimits.maxHeaderBytes
        if (headerByteCount > cap) {
            throw HttpHeaderLimitExceededException(
                limitName = "maxHeaderBytes",
                actual = headerByteCount,
                limit = cap,
            )
        }
    }

    // --- Emit / reset ---

    private fun emitHead(ctx: PipelineHandlerContext) {
        val parsedStatus = checkNotNull(status) { "status not parsed" }
        val parsedVersion = checkNotNull(version) { "version not parsed" }
        // RFC 9112 §6.3: both Content-Length and Transfer-Encoding present
        // is a smuggling vector — reject, matching HttpRequestDecoder.
        if (headers.isChunked && headers.contentLength != null) {
            throw HttpParseException(
                "Both Transfer-Encoding and Content-Length present (RFC 7230 §3.3.3)",
            )
        }
        // RFC 9110 §8.6: an invalid (negative) Content-Length is unrecoverable
        // framing — treating it as "no body" would let the body bytes be
        // parsed as the next response (response splitting).
        val cl = headers.contentLength
        if (cl != null && cl < 0L) {
            throw HttpParseException("Invalid Content-Length: $cl (RFC 9110 §8.6)")
        }
        val head = HttpResponseHead(parsedStatus, parsedVersion, headers)
        // Latch the framing decision off the head BEFORE dispatching it, so
        // whatever a downstream handler does with the headers cannot skew
        // the decoder's state transition (same hazard as the server
        // decoder's emitHead).
        val chunked = head.headers.isChunked
        val code = parsedStatus.code
        status = null
        version = null
        headers = HttpHeaders()

        when {
            code == SWITCHING_PROTOCOLS_CODE -> {
                // 101 answers its request — consume the method, then leave
                // HTTP: everything after the head belongs to the switched
                // protocol.
                pendingMethods.removeFirstOrNull()
                emitBodylessHead(ctx, head, State.PASS_THROUGH)
            }
            parsedStatus.isInformational -> {
                // Interim response (100 Continue etc.): bodyless, and the
                // queued request method stays for the final response.
                emitBodylessHead(ctx, head, State.READ_STATUS_LINE)
            }
            else -> emitFinalHead(ctx, head, cl, chunked, code)
        }
    }

    private fun emitFinalHead(
        ctx: PipelineHandlerContext,
        head: HttpResponseHead,
        cl: Long?,
        chunked: Boolean,
        code: Int,
    ) {
        val method = pendingMethods.removeFirstOrNull()
        when {
            // RFC 9112 §6.3 rule 1: HEAD responses never carry a body.
            method == HttpMethod.HEAD -> emitBodylessHead(ctx, head, State.READ_STATUS_LINE)
            // Rule 2: a 2xx to CONNECT turns the connection into a tunnel.
            method == HttpMethod.CONNECT && code in 200..299 ->
                emitBodylessHead(ctx, head, State.PASS_THROUGH)
            // Rule 1: 204 / 304 never carry a body.
            code == NO_CONTENT_CODE || code == NOT_MODIFIED_CODE ->
                emitBodylessHead(ctx, head, State.READ_STATUS_LINE)
            chunked -> {
                state = State.READ_CHUNK_SIZE
                bodyBytesRemaining = 0L
                ctx.propagateRead(head)
            }
            cl != null && cl > 0L -> {
                state = State.READ_FIXED_BODY
                bodyBytesRemaining = cl
                ctx.propagateRead(head)
            }
            cl != null -> emitBodylessHead(ctx, head, State.READ_STATUS_LINE)
            // Rule 8: no framing header — the body runs until the
            // connection closes.
            else -> {
                state = State.READ_UNTIL_CLOSE
                ctx.propagateRead(head)
            }
        }
    }

    private fun emitBodylessHead(ctx: PipelineHandlerContext, head: HttpResponseHead, nextState: State) {
        state = nextState
        ctx.propagateRead(head)
        ctx.propagateRead(HttpBodyEnd.EMPTY)
    }

    private fun emitLastWithTrailers(ctx: PipelineHandlerContext) {
        val trailers = chunkTrailers
        chunkTrailers = null
        state = State.READ_STATUS_LINE
        val last = if (trailers == null || trailers.isEmpty) {
            HttpBodyEnd.EMPTY
        } else {
            HttpBodyEnd(EmptyIoBuf, trailers)
        }
        ctx.propagateRead(last)
    }

    private fun resetState() {
        state = State.READ_STATUS_LINE
        accumulatorSize = 0
        status = null
        version = null
        headers = HttpHeaders()
        bodyBytesRemaining = 0L
        chunkTrailers = null
        chunkCrlfSeen = 0
        headerByteCount = 0
    }

    private companion object {
        /** Initial capacity of the fallback line accumulator, in bytes. */
        private const val INITIAL_ACCUMULATOR_CAPACITY = 256

        private val LF = '\n'.code.toByte()
        private val CR = '\r'.code.toByte()

        /** Maximum hex digits for a chunk size (16 hex digits = 2^64). */
        private const val MAX_CHUNK_SIZE_HEX_DIGITS = 16

        private const val CRLF_LENGTH = 2

        private const val SWITCHING_PROTOCOLS_CODE = 101
        private const val NO_CONTENT_CODE = 204
        private const val NOT_MODIFIED_CODE = 304
    }
}
