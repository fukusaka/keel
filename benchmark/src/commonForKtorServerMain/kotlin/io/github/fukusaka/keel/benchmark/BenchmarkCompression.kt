package io.github.fukusaka.keel.benchmark

import io.ktor.server.application.Application

/**
 * Installs Ktor's `Compression` plugin (gzip + deflate) on [Application]
 * when [enabled] is true. Implemented per-target because the plugin
 * artefact `ktor-server-compression` only ships for JVM — Native Ktor
 * adapters get a no-op `actual` and the bench's compression scenario
 * surfaces the gap as a missing `Content-Encoding` check failure.
 *
 * gzip + deflate cover the dominant `Accept-Encoding` values in CDN /
 * browser traffic. Brotli is intentionally omitted because Ktor's
 * `brotli()` provider pulls in an extra native dep that isn't justified
 * for a throughput bench — would also skew Native vs JVM comparison.
 */
internal expect fun Application.installBenchmarkCompression(enabled: Boolean)
