package io.github.fukusaka.keel.buf

/**
 * Thrown when an attempt to grow a multi-segment buffer past its
 * configured `maxCapacity` is rejected.
 *
 * Raised by the multi-seg `IoBuf` PoC (`buf.poc.*`) when
 * `appendSegment` would push the buffer's total capacity past the
 * cap that the caller (engine layer config — `BindConfig` /
 * `ConnectConfig` derived) supplied at construction. The cap is the
 * engine layer's defence against unbounded memory growth from a slow
 * peer or a malicious request that never finishes its header / body;
 * codec callers catch this exception and convert it to the
 * appropriate protocol-level error (HTTP 431 Request Header Fields
 * Too Large / 413 Payload Too Large / TLS record-size violation /
 * etc.).
 *
 * Also raised by `writeByte` / `writeByteArray` when the tail
 * segment is full — the multi-seg `IoBuf` does **not** auto-grow on
 * write. The caller must explicitly `appendSegment` (which itself
 * cap-checks) before further writes can land.
 *
 * **Provisional**: lives alongside the PoC interfaces (`buf.poc.*`).
 * Once the multi-seg `IoBuf` redesign lands the exception graduates
 * to the production `keel-io` surface; nothing about the type itself
 * is PoC-specific.
 */
class KeelBufferOverflowException(message: String) : RuntimeException(message)
