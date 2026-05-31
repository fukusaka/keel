@file:JsModule("zlib")
@file:JsNonModule

package io.github.fukusaka.keel.compression.zlib

import org.khronos.webgl.Uint8Array

// ---- Node zlib bindings (sync API) ----
//
// We use the buffer-at-a-time sync API (`gzipSync` / `deflateSync` /
// `gunzipSync` / `inflateSync` / `deflateRawSync` / `inflateRawSync`)
// because the Streaming `Transform` shape doesn't fit the
// `EncoderSession.update(buf): IoBuf` synchronous return contract
// without an internal buffer + drain dance. For the bench `/large` and
// typical HTTP response use case (full body buffered before write), the
// sync API is sufficient. A Streaming-API impl can be added later if
// per-chunk push compression becomes a hot path.
//
// `Buffer` is Node's binary-data wrapper; the cinterop / kotlin-js
// stdlib does not bind it directly, so we accept dynamic on the way out
// and copy bytes back into a Kotlin ByteArray.

@Suppress("FunctionName", "FunctionNaming")
@JsName("gzipSync")
internal external fun gzipSync(buf: Uint8Array): dynamic

@Suppress("FunctionName", "FunctionNaming")
@JsName("gunzipSync")
internal external fun gunzipSync(buf: Uint8Array): dynamic

@Suppress("FunctionName", "FunctionNaming")
@JsName("deflateSync")
internal external fun deflateSync(buf: Uint8Array): dynamic

@Suppress("FunctionName", "FunctionNaming")
@JsName("inflateSync")
internal external fun inflateSync(buf: Uint8Array): dynamic

// The raw-DEFLATE variants take an optional `{ finishFlush }` so the codec
// can terminate with `Z_SYNC_FLUSH` instead of `Z_FINISH`. RFC 7692
// permessage-deflate frames are sync-flushed raw-DEFLATE streams (each
// message ends in the `00 00 FF FF` empty-block marker, no final block);
// `deflateRawSync(buf, { finishFlush: Z_SYNC_FLUSH })` produces exactly the
// bytes the native / JVM streaming backends emit for `FlushMode.Sync`, and
// `inflateRawSync(buf, { finishFlush: Z_SYNC_FLUSH })` tolerates that
// non-final input instead of throwing "unexpected end of file".
@Suppress("FunctionName", "FunctionNaming")
@JsName("deflateRawSync")
internal external fun deflateRawSync(buf: Uint8Array, options: dynamic = definedExternally): dynamic

@Suppress("FunctionName", "FunctionNaming")
@JsName("inflateRawSync")
internal external fun inflateRawSync(buf: Uint8Array, options: dynamic = definedExternally): dynamic

/** Node `zlib.constants` (`Z_SYNC_FLUSH` / `Z_FULL_FLUSH` / `Z_FINISH` …). */
@JsName("constants")
internal external val constants: dynamic
