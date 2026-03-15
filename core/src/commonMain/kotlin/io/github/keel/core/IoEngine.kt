package io.github.keel.core

expect class IoEngine() {
    fun poll(timeoutMs: Long): Int
    fun close()
}
