package io.github.fukusaka.keel.buf

/**
 * Thrown when an [IoBuf] write would exceed its configured `maxCapacity`,
 * i.e. when no more [Segment]s can be appended to the underlying chain.
 *
 * Engines configure `maxCapacity` to bound buffer growth. Callers (codec /
 * handler code) must catch this and decide how to handle the overflow —
 * truncate the message, surface a protocol error, return `413 Payload Too
 * Large`, drop the connection, etc.
 *
 * Distinct from JDK's `BufferOverflowException` to keep the keel surface
 * self-contained on KMP and to convey the multi-segment-chain semantic
 * rather than a single-buffer position/limit overflow.
 */
public class KeelBufferOverflowException(message: String) : RuntimeException(message)
