package io.github.keel.core

actual class IoEngine actual constructor() {
    actual fun poll(timeoutMs: Long): Int = 0
    actual fun close() {}
}
