package io.github.fukusaka.keel.buf

/**
 * JS leak detection: no-op.
 *
 * [TypedArrayIoBuf] is backed by V8 GC-managed Int8Array.
 * close() and release() are no-ops for memory management.
 * No manual leak detection is needed.
 */
internal actual fun installLeakDetection(buf: IoBuf, onLeak: (String) -> Unit): IoBuf = buf
