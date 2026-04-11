package io.github.fukusaka.keel.io

import io.github.fukusaka.keel.buf.IoBuf

/**
 * JS: returns `null`. Kotlin/JS [ByteArray] backs onto an `Int8Array` internally,
 * but exposing it as a keel `IoBuf` without copying requires bridging through
 * `Int8ArrayIoBuf`, which is not implemented. Callers fall back to the chunked
 * copy path — Node.js is not in the performance hot path for `/large`.
 */
internal actual fun wrapBytesAsIoBuf(bytes: ByteArray, offset: Int, length: Int): IoBuf? = null
