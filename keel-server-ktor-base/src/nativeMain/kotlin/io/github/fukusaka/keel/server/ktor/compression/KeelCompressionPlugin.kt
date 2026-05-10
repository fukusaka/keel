/*
 * Copyright 2026 fukusaka. Licensed under the Apache License, Version 2.0.
 */
package io.github.fukusaka.keel.server.ktor.compression

import io.ktor.http.HeaderValue
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.content.compressed
import io.ktor.http.parseHeaderValue
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.PipelineCall
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.acceptEncoding
import io.ktor.server.response.ApplicationSendPipeline
import io.ktor.util.logging.KtorSimpleLogger
import io.ktor.util.pipeline.PipelineContext

/**
 * Native-only Ktor plugin providing response compression backed by
 * `keel-compression-zlib`.
 *
 * Closes the Native KeelCio* gap: ktor-server-compression is JVM-only,
 * and on Native ktor's stock `GZipEncoder` / `DeflateEncoder` are
 * identity-only no-op stubs. With this plugin installed, a Native ktor
 * server (e.g. backed by KeelCio) honours `Accept-Encoding` on incoming
 * requests and emits compressed `Content-Encoding: gzip` / `deflate`
 * payloads to the wire.
 *
 * ## API parity with ktor-server-compression
 *
 * The API surface mirrors `io.ktor.server.plugins.compression.Compression`
 * so users can write the same DSL on Native:
 *
 * ```kotlin
 * embeddedServer(KeelCio, port = 8080) {
 *     install(KeelCompression) {
 *         gzip()
 *         deflate()
 *         minimumSize(1024)
 *     }
 *     routing { get("/") { call.respondText("hello") } }
 * }
 * ```
 *
 * ## Scope
 *
 * Response compression only. Request decompression (inbound
 * `Content-Encoding`) is a separate plugin (`KeelContentEncodingPlugin`,
 * follow-up PR) so the two responsibilities can be enabled / disabled
 * independently.
 *
 * ## JVM
 *
 * On JVM continue to use `install(Compression)` from ktor-server-compression
 * — this plugin is `nativeMain`-only and does not appear in the JVM
 * source set.
 *
 * @see KeelCompressionConfig
 * @see KeelGZipEncoder
 * @see KeelDeflateEncoder
 */
public val KeelCompression: RouteScopedPlugin<KeelCompressionConfig> = createRouteScopedPlugin(
    name = "KeelCompression",
    createConfiguration = ::KeelCompressionConfig,
) {
    if (pluginConfig.encoders.none()) {
        pluginConfig.default()
    }
    val options = pluginConfig.buildOptions()

    on(KeelContentEncodingHook) { call ->
        encode(call, options)
    }
}

private val LOGGER = KtorSimpleLogger("io.github.fukusaka.keel.server.ktor.compression.KeelCompression")

/**
 * Internal hook that intercepts [ApplicationSendPipeline.ContentEncoding]
 * and exposes a `transformBody { ... }` method to the handler.
 *
 * Mirrors ktor-server-compression's `ContentEncoding` hook. Reused here
 * so the plugin's body reads identically.
 */
internal object KeelContentEncodingHook : Hook<suspend KeelContentEncodingHook.Context.(PipelineCall) -> Unit> {

    class Context(private val pipelineContext: PipelineContext<Any, PipelineCall>) {
        fun transformBody(block: (OutgoingContent) -> OutgoingContent?) {
            val transformed = block(pipelineContext.subject as OutgoingContent)
            if (transformed != null) {
                pipelineContext.subject = transformed
            }
        }
    }

    override fun install(
        pipeline: ApplicationCallPipeline,
        handler: suspend Context.(PipelineCall) -> Unit,
    ) {
        pipeline.sendPipeline.intercept(ApplicationSendPipeline.ContentEncoding) {
            handler(Context(this), call)
        }
    }
}

/**
 * Negotiate an encoder based on the request's `Accept-Encoding` and
 * encoder priorities, then transform the response body via
 * [OutgoingContent.compressed].
 *
 * Mirrors ktor-server-compression's response encode path. The header
 * rewrite (`Content-Encoding`, `Vary`, `Content-Length` strip) happens
 * automatically inside `compressed()` — keel does not duplicate that
 * logic.
 */
private fun KeelContentEncodingHook.Context.encode(
    call: PipelineCall,
    options: KeelCompressionOptions,
) {
    val acceptEncodingRaw = call.request.acceptEncoding()
    if (acceptEncodingRaw == null) {
        LOGGER.trace("Skip compression for ${call.request.local.uri}: no Accept-Encoding")
        return
    }

    val comparator = compareBy<Pair<KeelCompressionEncoderConfig, HeaderValue>>(
        { it.second.quality },
        { it.first.priority },
    ).reversed()

    val encoders = parseHeaderValue(acceptEncodingRaw)
        .filter { it.value == "*" || it.value in options.encoders }
        .flatMap { header ->
            when (header.value) {
                "*" -> options.encoders.values.map { it to header }
                else -> options.encoders[header.value]?.let { listOf(it to header) } ?: emptyList()
            }
        }
        .sortedWith(comparator)
        .map { it.first }

    if (encoders.isEmpty()) {
        LOGGER.trace("Skip compression for ${call.request.local.uri}: no matching encoder")
        return
    }

    transformBody { message ->
        if (options.conditions.any { !it.invoke(call, message) }) {
            LOGGER.trace("Skip compression for ${call.request.local.uri}: global preconditions not met")
            return@transformBody null
        }
        if (message.headers[HttpHeaders.ContentEncoding] != null) {
            LOGGER.trace("Skip compression for ${call.request.local.uri}: already encoded")
            return@transformBody null
        }
        val chosen = encoders.firstOrNull { e -> e.conditions.all { it.invoke(call, message) } }
        if (chosen == null) {
            LOGGER.trace("Skip compression for ${call.request.local.uri}: no encoder satisfies conditions")
            return@transformBody null
        }
        LOGGER.trace("Encoding response for ${call.request.local.uri} with ${chosen.encoder.name}")
        message.compressed(chosen.encoder)
    }
}
