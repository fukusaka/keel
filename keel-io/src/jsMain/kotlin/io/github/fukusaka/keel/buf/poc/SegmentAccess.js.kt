package io.github.fukusaka.keel.buf.poc

import io.github.fukusaka.keel.buf.IoBuf
import io.github.fukusaka.keel.buf.Segment

/**
 * JS-side stub actuals for the multi-seg IoBuf PoC's segment-access
 * shim. The PoC is scoped to JVM + Native (macOS / Linux); JS is
 * out of scope here so each actual simply throws. The stubs exist
 * only to satisfy the `expect`/`actual` contract — if any of these
 * is called from JS code, that is a bug.
 *
 * Removed alongside the rest of `buf.poc.*` once the multi-seg IoBuf
 * candidate decision lands.
 */

private const val JS_OUT_OF_SCOPE =
    "buf.poc segment-access shim is not implemented on JS — PoC is scoped to JVM + Native"

internal actual fun extractSegment(buf: IoBuf): Segment =
    throw UnsupportedOperationException(JS_OUT_OF_SCOPE)

internal actual fun segmentGetByte(seg: Segment, offset: Int): Byte =
    throw UnsupportedOperationException(JS_OUT_OF_SCOPE)

internal actual fun segmentPutByte(seg: Segment, offset: Int, value: Byte): Unit =
    throw UnsupportedOperationException(JS_OUT_OF_SCOPE)

internal actual fun segmentGetBytes(
    seg: Segment,
    srcOffset: Int,
    dest: ByteArray,
    destOffset: Int,
    length: Int,
): Unit = throw UnsupportedOperationException(JS_OUT_OF_SCOPE)

internal actual fun segmentPutBytes(
    seg: Segment,
    destOffset: Int,
    src: ByteArray,
    srcOffset: Int,
    length: Int,
): Unit = throw UnsupportedOperationException(JS_OUT_OF_SCOPE)
