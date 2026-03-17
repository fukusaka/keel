package io.github.keel.core

/**
 * Allocates and reclaims [NativeBuf] instances.
 *
 * Callers must not use a [NativeBuf] after passing it to [release].
 */
interface BufferAllocator {
    /** Allocates a buffer with at least [capacity] bytes. */
    fun allocate(capacity: Int): NativeBuf

    /**
     * Returns [buf] to this allocator.
     *
     * The caller must not use [buf] after this call.
     */
    fun release(buf: NativeBuf)
}

/**
 * Allocates a fresh [NativeBuf] on every call and frees it immediately on [release].
 *
 * Works on all targets. Intended for tests and environments where pooling is unnecessary.
 */
object HeapAllocator : BufferAllocator {
    override fun allocate(capacity: Int): NativeBuf = NativeBuf(capacity)
    override fun release(buf: NativeBuf) = buf.close()
}
