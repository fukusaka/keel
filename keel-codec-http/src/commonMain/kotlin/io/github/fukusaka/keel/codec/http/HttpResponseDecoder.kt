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
 * **Byte-offset parsing**: each read scans the current [IoBuf] for LF via
 * [IoBuf.getByte] and parses the matched line directly from the buffer's
 * byte range — no per-line `String`. The status-line version token is
 * looked up through [HttpVersion.fromBytes] and the 3-digit status code is
 * read digit-by-digit, so a well-formed status line allocates nothing on
 * the success path. When a line straddles two [IoBuf]s the straddling
 * bytes are copied into a reused byte accumulator and parsed from there
 * (the fallback path), mirroring [HttpRequestDecoder].
 *
 * **Zero-copy header views**: like [HttpRequestDecoder], header names and
 * values are stored as byte-range views over the recv buffer via
 * [HttpHeaders.addRange] on the fast path — no per-header `String`. The
 * emitted [HttpResponseHead] retains the recv buffer for the lifetime of
 * those views, so ownership of `head.headers` transfers to the downstream
 * consumer (the [HttpResponseBodyAggregator] / terminal handler), which
 * must call [HttpHeaders.release] once it is done with the head; the
 * decoder never releases the emitted instance. The in-progress
 * [HttpHeaders] is borrowed from [HttpHeadersPool] on first use and handed to
 * the head it filled; the slot then holds nothing until the next write borrows
 * again. A held-but-unemitted borrow is released on [onInactive] and on the
 * error / truncation reset. On the rare
 * straddled (fallback) path, names/values are materialised to `String`s
 * via [HttpHeaders.add] (ISO-8859-1 byte-as-char, lossless for RFC 9110
 * obs-text), and chunked trailers are always materialised — neither
 * introduces a recv-buffer lifetime coupling where a range view cannot be
 * safely retained.
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

    // Reusable scratch buffer for [bufRangeToString]. The rare String
    // materialisations on the fast path (error-message reconstruction of
    // the offending line) copy a byte range out of the current [IoBuf]
    // before calling `decodeToString`. Retaining one ByteArray per decoder
    // instance turns that per-call tmp allocation into a single
    // per-connection allocation grown on demand up to [headerLimits.maxLineSize].
    private var scratchBuffer: ByteArray = ByteArray(INITIAL_SCRATCH_CAPACITY)

    // Head fields of the response currently being parsed.
    private var status: HttpStatus? = null
    private var version: HttpVersion? = null

    // Borrowed from [HttpHeadersPool] so the underlying storage arrays are
    // reused across responses on the connection. Ownership is transferred
    // to the emitted [HttpResponseHead] at [emitHead]; the downstream
    // consumer is responsible for calling [HttpHeaders.release] on the
    // emitted instance once the response has been consumed. After
    // [emitHead] the slot holds nothing; the next write borrows.
    private val headers = BorrowedHeaders()

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
        // Any [headers] the decoder is still holding is a borrow that never
        // reached an emitted [HttpResponseHead]. Release it so a connection
        // closing part-way through a header block does not cost the pool a
        // slot; truncation paths above have already given it back, and a
        // connection whose last message completed holds nothing.
        //
        // The borrow goes back here, for the reason the request decoder's
        // [onInactive] spells out.
        //
        // Redundant as the state table stands: every state that can be holding
        // a borrow at the ending routes through [propagateTruncation] above,
        // which resets and so gives it back already. Measured — removing this
        // line fails no test. It stays as the unconditional guarantee, so a
        // state added later that does not truncate does not silently keep the
        // accumulator; the request decoder, whose `onInactive` has no such
        // routing, relies on this line outright.
        headers.recycle()
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
     * Tries to parse exactly one line from [buf].
     *
     * Returns `true` when a line was consumed (fast or fallback path) —
     * the caller should then re-check [buf] for more bytes. Returns
     * `false` when [buf] did not contain a line terminator; the remaining
     * bytes (if any) have been moved into [accumulator] and [buf] has been
     * drained, so [processBuffer] must return to wait for the next read.
     */
    private fun processOneLine(ctx: PipelineHandlerContext, buf: IoBuf): Boolean {
        val lfIndex = scanLf(buf, buf.readerIndex, buf.writerIndex)
        if (lfIndex < 0) {
            // No LF in this IoBuf — copy remainder to the accumulator for the
            // next read (bound enforced inside appendToAccumulator).
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
        enforceLineSizeCap(lineLength)
        buf.readerIndex = lfIndex + 1
        when (state) {
            State.READ_STATUS_LINE -> {
                parseStatusLineFast(buf, lineStart, lineLength)
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
                val size = chunkSizeInBuf(buf, lineStart, lineLength)
                if (size < 0L) throwInvalidChunkSizeFromBuf(buf, lineStart, lineLength)
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
            State.READ_FIXED_BODY, State.READ_CHUNK_DATA, State.READ_CHUNK_DATA_CRLF,
            State.READ_UNTIL_CLOSE, State.PASS_THROUGH,
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
        enforceLineSizeCap(effLength)
        try {
            when (state) {
                State.READ_STATUS_LINE -> {
                    parseStatusLineFallback(arr, 0, effLength)
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
                    val size = chunkSizeInArr(arr, 0, effLength)
                    if (size < 0L) throwInvalidChunkSizeFromArr(arr, 0, effLength)
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
                State.READ_FIXED_BODY, State.READ_CHUNK_DATA, State.READ_CHUNK_DATA_CRLF,
                State.READ_UNTIL_CLOSE, State.PASS_THROUGH,
                -> Unit // unreachable.
            }
        } finally {
            // Reset logical size so subsequent lines can reuse the ByteArray.
            accumulatorSize = 0
        }
    }

    // --- Accumulator management ---

    /**
     * Appends the [length] bytes of [buf] starting at [offset] to the
     * accumulator (no [buf] cursor advance — the caller manages
     * `readerIndex`).
     *
     * The size cap allows one byte beyond `maxLineSize` so that a line of
     * exactly the cap whose CRLF straddles the read boundary (CR arrives,
     * LF does not) is not rejected before [processLineFallback] strips the
     * CR — the definitive post-strip cap check happens there. The
     * accumulator is therefore bounded at `maxLineSize + 1` bytes.
     */
    private fun appendToAccumulator(buf: IoBuf, offset: Int, length: Int) {
        if (length == 0) return
        val newSize = accumulatorSize + length
        if (newSize > headerLimits.maxLineSize + 1) {
            enforceLineSizeCap(newSize)
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

    // --- Status line (fast path, IoBuf-backed) ---

    /**
     * Parses `HTTP-version SP status-code SP [reason-phrase]` (RFC 9112
     * §4) directly from the buffer range. The reason phrase is
     * informational and discarded; a status line without the reason
     * segment is tolerated. The version token goes through
     * [HttpVersion.fromBytes] (no `String` on the success path) and the
     * status code is read digit-by-digit.
     */
    private fun parseStatusLineFast(buf: IoBuf, start: Int, length: Int) {
        // New response — the cumulative header/trailer byte counter of the
        // previous response ends here (see [headerByteCount]).
        headerByteCount = 0
        val end = start + length
        val sp1 = indexOfByteInBuf(buf, start, end, SP)
        if (sp1 <= start) throwInvalidStatusLineFromBuf(buf, start, length)
        val sp2 = indexOfByteInBuf(buf, sp1 + 1, end, SP)
        val codeEnd = if (sp2 >= 0) sp2 else end
        val code = statusCodeInBuf(buf, sp1 + 1, codeEnd)
        if (code < 0) throwInvalidStatusCodeFromBuf(buf, start, length)
        version = HttpVersion.fromBytes(buf, start, sp1 - start)
        status = HttpStatus(code)
    }

    private fun throwInvalidStatusLineFromBuf(buf: IoBuf, start: Int, length: Int): Nothing {
        throw HttpParseException(
            "Invalid status line (expected 3 tokens): ${bufRangeToString(buf, start, length)}",
        )
    }

    private fun throwInvalidStatusCodeFromBuf(buf: IoBuf, start: Int, length: Int): Nothing {
        throw HttpParseException(
            "Invalid status code in status line: ${bufRangeToString(buf, start, length)}",
        )
    }

    // --- Status line (fallback path, ByteArray-backed) ---

    private fun parseStatusLineFallback(arr: ByteArray, start: Int, length: Int) {
        // See [parseStatusLineFast] for the reset rationale.
        headerByteCount = 0
        val end = start + length
        val sp1 = indexOfByteInArr(arr, start, end, SP)
        if (sp1 <= start) throwInvalidStatusLineFromArr(arr, start, length)
        val sp2 = indexOfByteInArr(arr, sp1 + 1, end, SP)
        val codeEnd = if (sp2 >= 0) sp2 else end
        val code = statusCodeInArr(arr, sp1 + 1, codeEnd)
        if (code < 0) throwInvalidStatusCodeFromArr(arr, start, length)
        version = HttpVersion.fromBytes(arr, start, sp1 - start)
        status = HttpStatus(code)
    }

    private fun throwInvalidStatusLineFromArr(arr: ByteArray, start: Int, length: Int): Nothing {
        throw HttpParseException(
            "Invalid status line (expected 3 tokens): ${arr.decodeToString(start, start + length)}",
        )
    }

    private fun throwInvalidStatusCodeFromArr(arr: ByteArray, start: Int, length: Int): Nothing {
        throw HttpParseException(
            "Invalid status code in status line: ${arr.decodeToString(start, start + length)}",
        )
    }

    // --- Header line (fast path, IoBuf-backed) ---

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
        // Value: (colon, end), trim OWS from both sides.
        val valStart = trimLeftInBuf(buf, colon + 1, end)
        val valEnd = trimRightInBuf(buf, valStart, end)
        // Store byte ranges into [buf] as zero-copy views instead of
        // materialising name/value into `String`s. The recv buffer is
        // retained by [HttpHeaders.addRange] for the lifetime of the views.
        val hash = HttpHeaders.caseInsensitiveHashOfBuf(buf, start, nameLen)
        val valueLen = valEnd - valStart
        headers.get().addRange(buf, hash, start, nameLen, valStart, valueLen)
        enforceHeaderCountCap(headers.get().size)
        enforceHeaderBytesCap(nameLen + valueLen)
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
        // Deliberately materialised `String`s + [HttpHeaders.add], not the
        // header fast path's zero-copy [HttpHeaders.addRange]: addRange
        // retains the recv buffer, and [HttpBodyEnd.trailers] has no release
        // path — every HttpBodyEnd consumer would inherit a
        // `trailers.release()` obligation or leak the buffer. Trailers are
        // rare, so the two small copies are cheap (mirrors HttpRequestDecoder).
        val name = bufAsciiToString(buf, start, nameLen)
        val valStart = trimLeftInBuf(buf, colon + 1, end)
        val valEnd = trimRightInBuf(buf, valStart, end)
        val value = bufAsciiToString(buf, valStart, valEnd - valStart)
        trailers.add(name, value)
        enforceHeaderCountCap(trailers.size)
        enforceHeaderBytesCap(name.length + value.length)
    }

    // --- Header line (fallback path, ByteArray-backed) ---

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
        val name = arrAsciiToString(arr, start, nameEnd)
        val valStart = trimLeftInArr(arr, colon + 1, end)
        val valEnd = trimRightInArr(arr, valStart, end)
        val value = arrAsciiToString(arr, valStart, valEnd)
        headers.get().add(name, value)
        enforceHeaderCountCap(headers.get().size)
        enforceHeaderBytesCap(name.length + value.length)
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
        val name = arrAsciiToString(arr, start, nameEnd)
        val valStart = trimLeftInArr(arr, colon + 1, end)
        val valEnd = trimRightInArr(arr, valStart, end)
        val value = arrAsciiToString(arr, valStart, valEnd)
        trailers.add(name, value)
        enforceHeaderCountCap(trailers.size)
        enforceHeaderBytesCap(name.length + value.length)
    }

    // --- Materialisation helpers (instance-scoped scratch) ---

    /**
     * Copies an [IoBuf] byte range into the reused [scratchBuffer] and
     * decodes it — used only for error-message reconstruction of the
     * offending line, so the UTF-8 [ByteArray.decodeToString] here is
     * best-effort (the success paths never materialise the line).
     */
    private fun bufRangeToString(buf: IoBuf, offset: Int, length: Int): String {
        val scratch = ensureScratchCapacity(length)
        for (i in 0 until length) scratch[i] = buf.getByte(offset + i)
        return scratch.decodeToString(0, length)
    }

    private fun ensureScratchCapacity(required: Int): ByteArray {
        val cur = scratchBuffer
        if (cur.size >= required) return cur
        // Double on demand, capped at headerLimits.maxLineSize (the same
        // bound enforced on the line length, so scratch never needs more).
        val newCap = minOf(headerLimits.maxLineSize, maxOf(required, cur.size * 2))
        val next = ByteArray(newCap)
        scratchBuffer = next
        return next
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

    private fun throwInvalidChunkSizeFromBuf(buf: IoBuf, start: Int, lineLen: Int): Nothing {
        throw HttpParseException(
            "Invalid chunk size: ${bufRangeToString(buf, start, lineLen)}",
        )
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
        // Latch the framing predicates off `headers` once, before building the
        // head and dispatching it (both are non-trivial getters, and `headers`
        // is handed to [head] below and this slot holds nothing after it). `chunked` here; `cl` after
        // the Content-Length validity gate so a single evaluation each feeds the
        // smuggling check, the negative-CL check, and the framing decision.
        val chunked = headers.get().isChunked
        // Reject a malformed / conflicting Content-Length before reading a value
        // (see [rejectInvalidContentLength]).
        rejectInvalidContentLength(headers.get())
        val cl = headers.get().contentLength
        // RFC 9112 §6.3: both Content-Length and Transfer-Encoding present
        // is a smuggling vector — reject, matching HttpRequestDecoder.
        if (chunked && cl != null) {
            throw HttpParseException(
                "Both Transfer-Encoding and Content-Length present (RFC 7230 §3.3.3)",
            )
        }
        // RFC 9110 §8.6: an invalid (negative) Content-Length is unrecoverable
        // framing — treating it as "no body" would let the body bytes be
        // parsed as the next response (response splitting).
        if (cl != null && cl < 0L) {
            throw HttpParseException("Invalid Content-Length: $cl (RFC 9110 §8.6)")
        }
        val head = HttpResponseHead(parsedStatus, parsedVersion, headers.transfer())
        val code = parsedStatus.code
        status = null
        version = null
        // The previous `headers` reference has been transferred to `head`;
        // downstream owns its lifecycle (and must release it); the slot holds
        // nothing until the next write borrows.

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
        // Error-path reset: the partially-filled `headers` borrow never
        // reached `emitHead`, so the decoder still owns it. Return it to
        // the pool; the next write borrows.
        headers.recycle()
        bodyBytesRemaining = 0L
        chunkTrailers = null
        chunkCrlfSeen = 0
        headerByteCount = 0
        // After a parse error / truncation the caller is expected to close
        // the connection, but clear the method queue anyway: a consumer
        // that keeps reading must not have later responses framed against
        // the stale methods of aborted exchanges (a HEAD entry applied to
        // the wrong response would desync the whole connection).
        pendingMethods.clear()
    }

    private companion object {
        /** Initial capacity of the fallback line accumulator, in bytes. */
        private const val INITIAL_ACCUMULATOR_CAPACITY = 256

        /** Initial capacity of the per-decoder scratch buffer, in bytes. */
        private const val INITIAL_SCRATCH_CAPACITY = 256

        private const val CRLF_LENGTH = 2

        private const val SWITCHING_PROTOCOLS_CODE = 101
        private const val NO_CONTENT_CODE = 204
        private const val NOT_MODIFIED_CODE = 304
    }
}

// --- File-scoped byte constants + stateless byte primitives ---
//
// Kept at file scope (not class members) so the decoder class stays under
// detekt's LargeClass limit; these are pure scanners over an [IoBuf] /
// [ByteArray] byte range and hold no decoder state.

private val LF = '\n'.code.toByte()
private val CR = '\r'.code.toByte()
private val SP = ' '.code.toByte()
private val HT = '\t'.code.toByte()
private val COLON = ':'.code.toByte()
private val SEMICOLON = ';'.code.toByte()

/** ASCII code points bounding the decimal status-code digits. */
private val DIGIT_ZERO = '0'.code
private val DIGIT_NINE = '9'.code
private const val DECIMAL_BASE = 10

/** Valid HTTP status-code range (RFC 9110 §15): 3 digits, 100..999. */
private const val MIN_STATUS_CODE = 100
private const val MAX_STATUS_CODE = 999

/** Maximum hex digits for a chunk size (16 hex digits = 2^64). */
private const val MAX_CHUNK_SIZE_HEX_DIGITS = 16

/** Sentinel returned by the stateless parsers for a malformed token. */
private const val PARSE_INVALID = -1

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

private fun hexDigit(b: Int): Int = when {
    b in '0'.code..'9'.code -> b - '0'.code
    b in 'a'.code..'f'.code -> b - 'a'.code + 10
    b in 'A'.code..'F'.code -> b - 'A'.code + 10
    else -> -1
}

// --- Stateless line-token parsers (return a sentinel on malformed input;
// the class turns the sentinel into the matching HttpParseException with a
// scratch-materialised message) ---

/**
 * Parses the status code in `[from, until)` of [buf] digit-by-digit and
 * returns it, or [PARSE_INVALID] when the range is empty, holds a non-digit,
 * or the value is not a valid 3-digit code (100..999). Reading digit-by-digit
 * with an early bail past [MAX_STATUS_CODE] matches the former
 * `substring(...).toIntOrNull()?.takeIf { it in 100..999 }` for every input
 * (leading zeros keep the numeric value; longer tokens overshoot the cap).
 */
private fun statusCodeInBuf(buf: IoBuf, from: Int, until: Int): Int {
    if (from >= until) return PARSE_INVALID
    var code = 0
    for (i in from until until) {
        val d = buf.getByte(i).toInt() and 0xFF
        if (d < DIGIT_ZERO || d > DIGIT_NINE) return PARSE_INVALID
        code = code * DECIMAL_BASE + (d - DIGIT_ZERO)
        if (code > MAX_STATUS_CODE) return PARSE_INVALID
    }
    return if (code < MIN_STATUS_CODE) PARSE_INVALID else code
}

/** [statusCodeInBuf] over a [ByteArray] range. */
private fun statusCodeInArr(arr: ByteArray, from: Int, until: Int): Int {
    if (from >= until) return PARSE_INVALID
    var code = 0
    for (i in from until until) {
        val d = arr[i].toInt() and 0xFF
        if (d < DIGIT_ZERO || d > DIGIT_NINE) return PARSE_INVALID
        code = code * DECIMAL_BASE + (d - DIGIT_ZERO)
        if (code > MAX_STATUS_CODE) return PARSE_INVALID
    }
    return if (code < MIN_STATUS_CODE) PARSE_INVALID else code
}

/**
 * Parses a chunk-size line `HEX *WSP [";" chunk-ext]` in `[start, start +
 * length)` of [buf] (RFC 9112 §7.1) and returns the size, or `-1L` when the
 * hex token is empty, over 16 digits, holds a non-hex byte, or overflows into
 * the sign bit. Chunk extensions are accepted and discarded.
 */
private fun chunkSizeInBuf(buf: IoBuf, start: Int, length: Int): Long {
    val end = start + length
    val extStart = indexOfByteInBuf(buf, start, end, SEMICOLON)
    val sizeEnd = if (extStart >= 0) extStart else end
    val hexLen = trimRightInBuf(buf, start, sizeEnd) - start
    if (hexLen == 0 || hexLen > MAX_CHUNK_SIZE_HEX_DIGITS) return -1L
    var value = 0L
    for (i in 0 until hexLen) {
        val digit = hexDigit(buf.getByte(start + i).toInt() and 0xFF)
        if (digit < 0) return -1L
        value = (value shl 4) or digit.toLong()
    }
    return if (value < 0L) -1L else value
}

/** [chunkSizeInBuf] over a [ByteArray] range. */
private fun chunkSizeInArr(arr: ByteArray, start: Int, length: Int): Long {
    val end = start + length
    val extStart = indexOfByteInArr(arr, start, end, SEMICOLON)
    val sizeEnd = if (extStart >= 0) extStart else end
    val hexLen = trimRightInArr(arr, start, sizeEnd) - start
    if (hexLen == 0 || hexLen > MAX_CHUNK_SIZE_HEX_DIGITS) return -1L
    var value = 0L
    for (i in 0 until hexLen) {
        val digit = hexDigit(arr[start + i].toInt() and 0xFF)
        if (digit < 0) return -1L
        value = (value shl 4) or digit.toLong()
    }
    return if (value < 0L) -1L else value
}

/**
 * ISO-8859-1 (byte-as-char) decode of an [IoBuf] byte range — lossless for
 * obs-text 0x80-0xFF header bytes (RFC 9110 §5.5). Used for materialised
 * trailer field names and values, matching the server decoder. A UTF-8
 * decode would corrupt lone high bytes to U+FFFD.
 */
private fun bufAsciiToString(buf: IoBuf, offset: Int, length: Int): String =
    ioBufToLatin1String(buf, offset, length)

/** ISO-8859-1 (byte-as-char) decode of a [ByteArray] range — see [bufAsciiToString]. */
private fun arrAsciiToString(arr: ByteArray, start: Int, end: Int): String {
    val length = end - start
    if (length == 0) return ""
    val chars = CharArray(length)
    for (i in 0 until length) chars[i] = (arr[start + i].toInt() and 0xFF).toChar()
    return chars.concatToString()
}
