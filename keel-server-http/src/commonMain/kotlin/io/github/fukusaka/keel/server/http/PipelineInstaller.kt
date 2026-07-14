package io.github.fukusaka.keel.server.http

import io.github.fukusaka.keel.buf.BufferAllocator
import io.github.fukusaka.keel.pipeline.Pipeline

/**
 * Wire-level extension point for the HTTP server pipeline.
 *
 * An installer adds handlers to a freshly-built per-connection [Pipeline],
 * between the HTTP/1 codec and the terminal `HttpServerHandler`. Installers
 * registered via [dsl.KeelHttpServerBuilder.installPipeline] run **in
 * registration order** — the first registered installs closest to the codec,
 * so ordering is expressed by registration sequence rather than an explicit
 * phase mechanism.
 *
 * This is distinct from [Middleware]: a [Middleware] wraps the per-call
 * request/response dispatch, whereas a [PipelineInstaller] operates at the
 * transport/handler layer (e.g. response compression, request decompression).
 * The built-in `compression { }` DSL is itself implemented on top of this hook.
 *
 * [install] is invoked once per connection during pipeline construction, on
 * the owning EventLoop thread, with the connection's [BufferAllocator].
 */
public fun interface PipelineInstaller {
    /**
     * Adds this installer's handlers to [pipeline]. Use [allocator] for any
     * pooled buffers the installed handlers need; handlers are responsible
     * for releasing what they allocate (see the leak-detection rules).
     */
    public fun install(pipeline: Pipeline, allocator: BufferAllocator)
}
