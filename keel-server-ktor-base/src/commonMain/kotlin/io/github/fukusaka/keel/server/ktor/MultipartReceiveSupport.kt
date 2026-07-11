package io.github.fukusaka.keel.server.ktor

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.cio.CIOMultipartDataBase
import io.ktor.http.content.MultiPartData
import io.ktor.server.application.call
import io.ktor.server.application.receiveType
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.request.ApplicationReceivePipeline
import io.ktor.server.request.header
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.InternalAPI
import kotlinx.coroutines.Dispatchers

/**
 * Installs a [MultiPartData] receive transformer backed by [CIOMultipartDataBase]
 * from `ktor-http-cio` (available in `commonMain` on all KMP targets).
 *
 * Ktor's built-in `defaultPlatformTransformations` handles [MultiPartData] on JVM
 * but returns `null` on Native platforms, causing `call.receiveMultipart()` to fail
 * with HTTP 415 Unsupported Media Type on Native keel engines. This interceptor fills
 * the gap: when the receive pipeline requests [MultiPartData] and the current body
 * is still a raw [ByteReadChannel], it wraps the channel in [CIOMultipartDataBase].
 *
 * On JVM, Ktor's built-in interceptor fires first (subscribed by [BaseApplicationEngine]
 * before keel's subscription) and calls `proceedWith(multipartData)`. Subsequent
 * interceptors — including this one — receive [MultiPartData] as `subject`, not
 * [ByteReadChannel], so the `subject as? ByteReadChannel` guard returns early. This
 * interceptor is a no-op on JVM.
 *
 * **Test strategy**: no standalone contract test. The transform is a receive-
 * pipeline interceptor whose behaviour only manifests when a real request body
 * is routed through `call.receiveMultipart()`, which needs a Ktor engine + call.
 * That end-to-end path is covered by `keel-server-ktor`'s
 * `KeelEngineMultipartTest`; a standalone test would re-stage the same engine
 * call.
 */
@OptIn(InternalAPI::class)
internal fun ApplicationReceivePipeline.installMultipartTransform() {
    intercept(ApplicationReceivePipeline.Transform) {
        val channel = subject as? ByteReadChannel ?: return@intercept
        if (call.receiveType.type != MultiPartData::class) return@intercept
        val contentTypeHeader = call.request.header(HttpHeaders.ContentType)
            ?: throw UnsupportedMediaTypeException(ContentType.MultiPart.FormData)
        val contentLength = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        proceedWith(
            CIOMultipartDataBase(
                coroutineContext + Dispatchers.Unconfined,
                channel,
                contentTypeHeader,
                contentLength,
            ),
        )
    }
}
