package io.github.fukusaka.keel.benchmark

import io.github.fukusaka.keel.buf.DefaultAllocator
import io.github.fukusaka.keel.codec.http.CompressionHandler
import io.github.fukusaka.keel.compression.CompressionRegistry
import io.github.fukusaka.keel.compression.zlib.DeflateCodec
import io.github.fukusaka.keel.compression.zlib.GzipCodec
import io.github.fukusaka.keel.pipeline.PipelinedChannel
import io.github.fukusaka.keel.server.ktor.KeelApplicationEngine

/**
 * Pipeline customizer for `Keel` ApplicationEngine variants on Native
 * (`ktor-keel-kqueue` / `-nwconnection` / `-epoll` / `-io-uring`)
 * — installs `CompressionHandler` from `keel-codec-http` with the
 * `keel-compression-zlib` backend's `gzip` + `deflate` codecs.
 *
 * Returns `null` when [enabled] is false so the [KeelApplicationEngine.Configuration.pipelineCustomizer]
 * field stays at its default (no extra handler installed).
 *
 * **Scope**: applies only to the `Keel` engine variant (which uses
 * `keel-codec-http` parser and routes responses through keel's
 * `HttpResponseHead` + `HttpBody*` pipeline). The `KeelCio` variant
 * uses `ktor-http-cio`'s raw byte-channel parser and bypasses keel's
 * codec pipeline; compression for `KeelCio*` Native engines is a
 * separate integration tracked in `plan.md` under `Compression
 * backend 拡張`.
 *
 * **Why per-channel registry**: each `pipelineCustomizer` invocation
 * is one accepted connection. We allocate a fresh `CompressionRegistry`
 * per channel rather than sharing one across the server because
 * `CompressionHandler` retains state (active session, accept-encoding
 * queue) per-channel anyway, and the registry itself is small + the
 * per-channel allocation is dwarfed by `CompressionHandler`'s
 * per-channel scratch IoBuf.
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
    }
}
