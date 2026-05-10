package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.codec.http.CompressionHandler
import io.github.fukusaka.keel.codec.http.HttpRequestDecompressionHandler
import io.github.fukusaka.keel.compression.CompressionRegistry
import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import io.github.fukusaka.keel.compression.zlib.GzipCodec
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.server.ktor.KeelApplicationEngine

/**
 * Pipeline customizer for `Keel` ApplicationEngine variants on Native
 * (`ktor-keel-kqueue` / `-nwconnection` / `-epoll` / `-io-uring`).
 * Installs both compression handlers from `keel-codec-http`:
 *
 * - **Outbound** [CompressionHandler] — server-side response
 *   compression (`Content-Encoding`) negotiated against the request's
 *   `Accept-Encoding`.
 * - **Inbound** [HttpRequestDecompressionHandler] — server-side
 *   request body decoding (`Content-Encoding`) with the secure-by-default
 *   dual-gate zip-bomb defence (1 MiB absolute cap + 100:1 ratio +
 *   burst 3, registry-driven encoding lookup).
 *
 * Both handlers share one [CompressionRegistry] instance (registered
 * with `gzip` + `deflate` codecs from `keel-compression-zlib`) per
 * accepted connection — they look up encoder / decoder pairs from the
 * same map.
 *
 * Returns `null` when [enabled] is false so the
 * [KeelApplicationEngine.Configuration.pipelineCustomizer] field stays
 * at its default (no extra handler installed).
 *
 * **Scope**: applies only to the `Keel` engine variant (which uses
 * `keel-codec-http` parser and routes through keel's pipeline
 * messages). The `KeelCio` variant uses `ktor-http-cio`'s raw
 * byte-channel parser and bypasses keel's codec pipeline;
 * compression / decompression for `KeelCio*` Native engines is wired
 * at the application layer via `KeelCompressionPlugin` (response,
 * landed) / `KeelContentEncodingPlugin` (request, follow-up PR) in
 * `keel-server-ktor-base/nativeMain`.
 *
 * **Why per-channel registry**: each `pipelineCustomizer` invocation
 * is one accepted connection. We allocate a fresh `CompressionRegistry`
 * per channel rather than sharing one across the server because the
 * handlers retain state (active session, accept-encoding queue,
 * absolute / ratio counters) per-channel anyway, and the registry
 * itself is small + the per-channel allocation is dwarfed by each
 * handler's scratch IoBuf.
 *
 * **Pipeline order**: the customizer adds [CompressionHandler] first,
 * then [HttpRequestDecompressionHandler]. Order of `addLast` is
 * irrelevant for correctness because the two handlers operate on
 * disjoint pipeline directions (outbound vs inbound), but adding the
 * outbound handler first matches the pre-PR pipeline shape and keeps
 * `git blame` / diff narrow.
 */
internal fun keelNativeCompressionCustomizer(
    enabled: Boolean,
): ((PipelinedChannel) -> Unit)? {
    if (!enabled) return null
    return { channel ->
        val registry = CompressionRegistry().apply {
            register(GzipCodec)
            register(DeflateCodec)
        }
        channel.pipeline.addLast(
            "compression",
            CompressionHandler(registry = registry, allocator = DefaultAllocator),
        )
        channel.pipeline.addLast(
            "request-decompression",
            HttpRequestDecompressionHandler(registry = registry, allocator = DefaultAllocator),
        )
    }
}
