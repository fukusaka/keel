package io.github.fukusaka.keel.codec.http

import io.github.fukusaka.keel.buf.EmptyIoBuf
import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.ioBufToLatin1String
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
 * [headerLimits.maxLineSize] at most and its backing `ByteArray` is retained
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
 *
 * **Ending**: once the connection has ended ([onInactive]) the decoder
 * decodes nothing more. The ending can be raised from inside the decoder's
 * own downstream dispatch — a handler closing the channel from a request
 * head, as a server's shutdown drain does — with the parse frame still on
 * the stack; the rest of that read, later reads, and reads the pipeline
 * replays from its journal are then drained unparsed. Nothing decoded after
 * the ending could be answered, so nothing is emitted and no accumulator is
 * borrowed for it. (The head that raised the ending still finishes its own
 * emission: a bodyless head's empty [HttpBodyEnd] follows the ending, as it
 * always did; it carries no bytes.) A request cut by the ending is discarded
 * without an error:
 * nobody is waiting on it. (The response decoder, whose caller is, reports
 * the truncation first.)
 */
class HttpRequestDecoder(
    /**
     * Per-request header limits enforced during parsing — currently a
     * `maxHeaderCount` cap that aborts the parse with
     * [HttpHeaderLimitExceededException] when the configured count is
     * exceeded. Defaults to [HttpHeaderLimitsConfig.DEFAULT] (100
     * headers). The same cap is applied to chunked-trailer blocks, so
     * a malicious peer cannot bypass it by flooding trailers.
     */
    private val headerLimits: HttpHeaderLimitsConfig = HttpHeaderLimitsConfig.DEFAULT,
) : TypedInboundHandler<IoBuf>(IoBuf::class, autoRelease = false) {

    override val producedType: KClass<*> get() = HttpMessage::class

    private enum class State {
        READ_REQUEST_LINE,
        READ_HEADERS,
        READ_FIXED_BODY,
        READ_CHUNK_SIZE,
        READ_CHUNK_DATA,
        READ_CHUNK_DATA_CRLF,
        READ_CHUNK_TRAILER,

        /**
         * The connection has ended. Nothing after it is decoded: the rest of
         * the read that carried the ending, later reads, and reads the
         * pipeline replays from its journal are all drained unparsed, and
         * nothing is emitted or borrowed for them. Terminal -- see [state].
         */
        ENDED,
    }

    /**
     * The parse state. [State.ENDED] is absorbing: once the connection has
     * ended nothing moves the decoder out of it. This decoder needs that:
     * [emitHead] writes the next state *after* the downstream dispatch that
     * can raise the ending returns, and that write is what the setter
     * absorbs. Every transition writes this property, so the rule lives here
     * and nowhere else.
     */
    private var state: State = State.READ_REQUEST_LINE
        set(next) {
            if (field != State.ENDED) field = next
        }

    // Fallback accumulator — lazily allocated on the first cross-IoBuf line.
    // `null` in the steady state where every line fits in a single IoBuf.
    // The `ByteArray` itself is retained across requests (even across
    // connection errors) so that a connection that once triggered a
    // partial read does not keep reallocating; only `accumulatorSize` is
    // reset between lines.
    //
    // Deliberately a heap ByteArray, not a pooled `IoBufAccumulator`
    // (considered 2026-07-02, rejected): the consumers are ByteArray-bound
    // (`decodeToString`, the `*InArr` parse helpers) so a flatten copy
    // would remain; the steady state never allocates this at all
    // (straddle-only); and growth is capped at `maxLineSize` and
    // amortised over the connection lifetime (a handful of doublings,
    // ever). Pooling would add ref-count lifecycle to a rare,
    // correctness-critical path for no steady-state gain.
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
    // to [headerLimits.maxLineSize] (same cap as the accumulator). The scratch
    // buffer is only valid for the duration of a single
    // `bufRangeToString` call — the returned `String` copies its
    // contents — so no lifecycle handling beyond growth is needed.
    private var scratchBuffer: ByteArray = ByteArray(INITIAL_SCRATCH_CAPACITY)

    private var method: HttpMethod? = null
    private var uri: String? = null
    private var version: HttpVersion? = null

    // Borrowed from [HttpHeadersPool] so the two underlying
    // `LinkedHashMap` bucket arrays are reused across requests on the
    // connection. Ownership is transferred to the emitted
    // [HttpRequestHead] at [emitHead]; the downstream
    // [HttpServerHandler] is responsible for calling
    // [HttpHeaders.release] on the emitted instance once the response
    // has been written. After [emitHead] the slot holds nothing; the
    // next write borrows.
    private val headers = BorrowedHeaders()

    private var bodyBytesRemaining: Long = 0L

    /**
     * Cumulative byte total `(nameLen + valueLen)` of every header
     * (and trailer) field admitted so far for the in-progress
     * request. Reset to `0` at the **next request line**
     * ([parseRequestLineFast] / [parseRequestLineFallback]) and on
     * the error path ([resetState]) — deliberately **not** at
     * [emitHead], because trailer field bytes parsed after the head
     * is emitted accumulate on top of the header bytes for the same
     * request. A malicious peer that splits its flood between the
     * header block and the chunked trailer block is therefore caught
     * by the same cap.
     */
    private var headerByteCount: Int = 0

    // Trailer accumulator for READ_CHUNK_TRAILER. Null until the first
    // trailer line is encountered; reset after emitting HttpBodyEnd.
    private var chunkTrailers: HttpHeaders? = null

    // CRLF consumption progress for READ_CHUNK_DATA_CRLF. Tracks how
    // many of the 2 expected bytes (CR, LF) have been consumed across
    // partial reads.
    private var chunkCrlfSeen: Int = 0

    // True once [HttpRequestStarted] has been emitted for the request currently
    // being parsed. Reset whenever the parser leaves READ_REQUEST_LINE, so the next
    // entry into a request line (a new request, after the previous one's
    // HttpBodyEnd) re-announces. Powers the header-complete deadline: the downstream
    // RequestDeadlineHandler arms its timer on HttpRequestStarted and disarms on the
    // HttpRequestHead. See processBuffer.
    private var requestStartAnnounced: Boolean = false

    override fun onInactive(ctx: PipelineHandlerContext) {
        // Any accumulator the decoder is still holding is borrowed from
        // [HttpHeadersPool] and has not reached a [HttpRequestHead], so the
        // only remaining reference is this field — a connection that closes
        // part-way through a header block would otherwise cost the pool a
        // slot. A connection whose last message completed holds nothing here,
        // and this is a no-op for it.
        //
        // The borrow goes back here. It is the same recycle an abandoned
        // parse performs -- the accumulator never reached a message, so it is
        // still this decoder's to give back.
        headers.recycle()
        // And the decoder is done: nothing decoded after the ending could be
        // answered, so nothing after it is decoded. This may run from inside
        // the downstream dispatch of a head, with the parse frame still on
        // the stack; the frame sees the state when it next looks and stops.
        state = State.ENDED
        ctx.propagateInactive()
    }

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
            // Announce the start of a request exactly once, on the first byte of its
            // request line. Detecting the transition into READ_REQUEST_LINE (rather
            // than flagging every `state = READ_REQUEST_LINE` site) also covers the
            // HTTP-pipelining case where a second request's line begins in the same
            // buffer right after the first request's HttpBodyEnd.
            if (state == State.READ_REQUEST_LINE) {
                if (!requestStartAnnounced) {
                    requestStartAnnounced = true
                    ctx.propagateUserEvent(HttpRequestStarted)
                }
            } else {
                requestStartAnnounced = false
            }
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
                // Nothing after the ending is decoded; the read is released by
                // the caller as always, and nothing is emitted or borrowed for it.
                State.ENDED -> return
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
            // next read. Enforces headerLimits.maxLineSize inside appendToAccumulator.
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
            State.READ_CHUNK_DATA_CRLF, State.ENDED,
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
        // The line is assembled: the accumulator is consumed here, before it
        // is parsed, so that `accumulatorSize > 0` means exactly "a line is
        // still pending" -- the invariant the response decoder's ending
        // relies on, kept the same on both sides. Nothing on this decoder's
        // ending path reads the size, so here the move changes no behaviour
        // (measured: with the reset back after the parse, no case fails).
        // The bytes stay in the array; the parsers below take their length
        // explicitly.
        accumulatorSize = 0
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
            State.READ_CHUNK_DATA_CRLF, State.ENDED,
            -> Unit // unreachable.
        }
    }

    // --- Accumulator management ---

    private fun appendToAccumulator(buf: IoBuf, offset: Int, length: Int) {
        if (length == 0) return
        val newSize = accumulatorSize + length
        enforceLineSizeCap(newSize)
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
            // Double, capped at headerLimits.maxLineSize so that the accumulator cannot
            // grow past the hard line-size limit.
            minOf(headerLimits.maxLineSize, maxOf(required, cur.size * 2))
        }
        val next = ByteArray(newCap)
        if (cur != null && accumulatorSize > 0) {
            cur.copyInto(next, 0, 0, accumulatorSize)
        }
        accumulator = next
    }

    // --- Line parsing (fast path, IoBuf-backed) ---

    private fun parseRequestLineFast(buf: IoBuf, start: Int, length: Int) {
        // New request — reset the cumulative header / trailer byte
        // counter. The previous request's `headerByteCount` was
        // intentionally kept across `emitHead` so trailer bytes
        // accumulate on top of header bytes (cannot be bypassed via
        // the trailer block); now that a new request line has
        // arrived, the cumulative count starts fresh.
        headerByteCount = 0
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
        // Value: (colon, end), trim OWS from both sides.
        val valStart = trimLeftInBuf(buf, colon + 1, end)
        val valEnd = trimRightInBuf(buf, valStart, end)

        // Store byte ranges into [buf] as zero-copy
        // views instead of materialising name/value into `String`s. The
        // recv buffer is retained by [HttpHeaders.addRange] for the
        // lifetime of the views.
        val hash = HttpHeaders.caseInsensitiveHashOfBuf(buf, start, nameLen)
        val valueLen = valEnd - valStart
        headers.get().addRange(buf, hash, start, nameLen, valStart, valueLen)
        enforceHeaderCountCap(headers.get().size)
        enforceHeaderBytesCap(nameLen + valueLen)
    }

    private fun throwInvalidRequestLineFromBuf(buf: IoBuf, start: Int, length: Int): Nothing {
        throw HttpParseException(
            "Invalid request line (expected 3 tokens): ${bufRangeToString(buf, start, length)}",
        )
    }

    // --- Line parsing (fallback path, ByteArray-backed) ---

    private fun parseRequestLineFallback(arr: ByteArray, start: Int, length: Int) {
        // See [parseRequestLineFast] for the reset rationale.
        headerByteCount = 0
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
        val name = arrAsciiToString(arr, start, nameEnd)

        val valStart = trimLeftInArr(arr, colon + 1, end)
        val valEnd = trimRightInArr(arr, valStart, end)
        val value = arrAsciiToString(arr, valStart, valEnd)

        headers.get().add(name, value)
        enforceHeaderCountCap(headers.get().size)
        enforceHeaderBytesCap(name.length + value.length)
    }

    /**
     * Aborts the parse with [HttpHeaderLimitExceededException] when
     * the number of header (or trailer) fields admitted so far exceeds
     * the configured [HttpHeaderLimitsConfig.maxHeaderCount] cap. The
     * check runs after the slot has been written so [actual] reflects
     * the actual oversize value (one past the cap), which the caller
     * surfaces verbatim in the error log.
     */
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

    /**
     * Accumulates the freshly-added field's `name + value` byte count
     * into [headerByteCount] and aborts the parse with
     * [HttpHeaderLimitExceededException] when the running total
     * exceeds [HttpHeaderLimitsConfig.maxHeaderBytes]. Headers and
     * trailers share the same accumulator so a flood split between
     * the two blocks cannot bypass the cap.
     */
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

    /**
     * Aborts the parse with the appropriate
     * [HttpHeaderLimitExceededException] subtype when the just-parsed
     * line exceeds [HttpHeaderLimitsConfig.maxLineSize]. The
     * request-line case raises [HttpUriLengthExceededException] (so a
     * response mapper can dispatch it to [HttpStatus.URI_TOO_LONG],
     * 414); every other line type raises the generic exception
     * (→ [HttpStatus.REQUEST_HEADER_FIELDS_TOO_LARGE], 431).
     */
    private fun enforceLineSizeCap(actualLength: Int) {
        val cap = headerLimits.maxLineSize
        if (actualLength <= cap) return
        if (state == State.READ_REQUEST_LINE) {
            throw HttpUriLengthExceededException(actual = actualLength, limit = cap)
        }
        throw HttpHeaderLimitExceededException(
            limitName = "maxLineSize",
            actual = actualLength,
            limit = cap,
        )
    }

    private fun throwInvalidRequestLineFromArr(arr: ByteArray, start: Int, length: Int): Nothing {
        throw HttpParseException(
            "Invalid request line (expected 3 tokens): ${arr.decodeToString(start, start + length)}",
        )
    }

    // --- Byte-level primitives ---

    private fun bufRangeToString(buf: IoBuf, offset: Int, length: Int): String {
        val scratch = ensureScratchCapacity(length)
        for (i in 0 until length) scratch[i] = buf.getByte(offset + i)
        return scratch.decodeToString(0, length)
    }

    private fun ensureScratchCapacity(required: Int): ByteArray {
        val cur = scratchBuffer
        if (cur.size >= required) return cur
        // Double on demand, capped at headerLimits.maxLineSize (the same bound the
        // fast path enforces on `lineLength`, so scratch never needs to
        // hold more than that).
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
        // header fast path's zero-copy [HttpHeaders.addRange] (considered
        // 2026-07-02, rejected): addRange retains the recv buffer, and
        // [HttpBodyEnd.trailers] has no release path — every HttpBodyEnd
        // consumer would inherit a `trailers.release()` obligation or leak
        // the buffer. Trailers are rare, so the two small copies are cheap.
        val name = bufAsciiToString(buf, start, nameLen)
        val valStart = trimLeftInBuf(buf, colon + 1, end)
        val valEnd = trimRightInBuf(buf, valStart, end)
        val value = bufAsciiToString(buf, valStart, valEnd - valStart)
        trailers.add(name, value)
        enforceHeaderCountCap(trailers.size)
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
        if (parsedVersion == HttpVersion.HTTP_1_1 && HttpHeaderName.HOST !in headers.get()) {
            throw HttpParseException("Missing required Host header (RFC 7230 §5.4)")
        }
        // RFC 9110 §8.6 / RFC 9112 §6.3: reject a malformed or conflicting
        // Content-Length before framing the body (see [rejectInvalidContentLength]).
        // Ordered before the smuggling check to match HttpResponseDecoder.
        rejectInvalidContentLength(headers.get())
        // RFC 7230 §3.3.3: reject requests with both Content-Length and Transfer-Encoding
        // to prevent HTTP Request Smuggling.
        if (headers.get().isChunked && headers.get().contentLength != null) {
            throw HttpParseException(
                "Both Transfer-Encoding and Content-Length present (RFC 7230 §3.3.3)",
            )
        }
        val head = HttpRequestHead(
            checkNotNull(method) { "method not parsed" },
            checkNotNull(uri) { "uri not parsed" },
            parsedVersion,
            headers.transfer(),
        )
        // Reset parser state before emitting to allow re-entrant pipeline processing.
        // The previous `headers` reference has been transferred to `head`;
        // downstream owns its lifecycle; the slot holds nothing until the
        // next write borrows.
        method = null
        uri = null
        version = null
        // [headerByteCount] is **not** reset here — trailer bytes
        // accumulate on top of the header bytes for the same request
        // (a malicious peer cannot bypass the cumulative cap by
        // stuffing fields into the trailer block). The counter is
        // reset at the start of the next request line — see
        // [parseRequestLineFast] / [parseRequestLineFallback].
        // Latch the body-framing decision off [head]'s headers BEFORE
        // dispatching the head downstream. A downstream handler may
        // release `head.headers` inside its `onRead` (e.g. the pipeline-
        // http sample handler does so eagerly to return the recv buffer
        // to the io-uring provided buffer ring), and the pooled
        // [HttpHeaders] instance resets `slotCount` to 0 on release —
        // reading `head.headers.contentLength` / `isChunked` after
        // `propagateRead` would then collapse to `null` / `false` and
        // misclassify the request as "no body", parsing the body bytes
        // as the next request line. Read the values into locals first
        // and the decoder's framing decision becomes immune to whatever
        // a handler does with the head.
        val cl = head.headers.contentLength
        val chunked = head.headers.isChunked
        ctx.propagateRead(head)

        when {
            chunked -> {
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
        // Once the connection has ended no parse runs, so no error reaches
        // this reset; if one did, the property would absorb the write.
        state = State.READ_REQUEST_LINE
        accumulatorSize = 0
        method = null
        uri = null
        version = null
        // Error-path reset: the partially-filled accumulator never
        // reached `emitHead`, so the decoder still owns it. Return it
        // to the pool; the next write borrows.
        headers.recycle()
        bodyBytesRemaining = 0L
        chunkTrailers = null
        chunkCrlfSeen = 0
        // After an aborted parse the next inbound byte begins a fresh request line
        // (if the connection survives), so re-announce it.
        requestStartAnnounced = false
        headerByteCount = 0
    }

    private companion object {
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
         * [headerLimits.maxLineSize].
         */
        private const val INITIAL_SCRATCH_CAPACITY = 256

        private val SEMICOLON = ';'.code.toByte()

        /** Maximum hex digits for a chunk size (16 hex digits = 2^64). */
        private const val MAX_CHUNK_SIZE_HEX_DIGITS = 16

        private const val CRLF_LENGTH = 2
    }
}

// --- Byte-level primitives, file-level ---
//
// Pure functions of their arguments, at file level as [HttpResponseDecoder]
// keeps its own. They need none of the class's state, and the class sits
// close to detekt's `LargeClass` limit: an earlier shape of the terminal-state
// change crossed it, and this is what kept the gate where it was. Measured on the epoll pipeline server, the move alone is also worth
// about 13% at 16 threads / 500 connections -- not the reason it was made,
// but a reason it stays.

private val LF = '\n'.code.toByte()
private val CR = '\r'.code.toByte()
private val SP = ' '.code.toByte()
private val HT = '\t'.code.toByte()
private val COLON = ':'.code.toByte()

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

/**
 * ISO-8859-1 (byte-as-char) decode of an [IoBuf] byte range. Used for
 * header / trailer field names and values so every materialisation
 * path agrees with the fast-path [io.github.fukusaka.keel.buf.IoBufAsciiText]
 * view: RFC 7230 §3.2.4 treats obs-text (0x80-0xFF) as opaque data,
 * and byte-as-char is lossless / reversible (unlike a UTF-8 decode,
 * which replaces lone high bytes with U+FFFD).
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
