package io.github.fukusaka.keel.engine.iouring

/**
 * io_uring registered buffer (Fixed Buffer) management strategy.
 *
 * Selects how (and whether) the engine pre-registers buffers with the kernel
 * via `io_uring_register_buffers` so that `IORING_OP_SEND_ZC_FIXED` can skip
 * the per-send page-pinning step. Each EventLoop holds its own registry — the
 * value here picks the implementation class, not the per-EL granularity
 * (per-EL is required by `IORING_SETUP_SINGLE_ISSUER`: every
 * `io_uring_register_*` must run on the ring's submitter task).
 *
 * **Vocabulary**: kernel docs and `liburing` use "registered buffer" and
 * "fixed buffer" interchangeably. This codebase standardises on
 * `Registered*` in code (matching `io_uring_register_buffers*` syscall
 * names and the existing `StaticRegisteredBufferRegistry` /
 * `IoUringRegisteredBufferOps` types) and "Fixed Buffer" in narrative
 * documentation (matching the `IORING_OP_SEND_ZC_FIXED` opcode).
 *
 * Configured per engine via the `IoUringEngine` constructor's
 * `registeredBufferStrategy` parameter, together with
 * `registeredBufferSlotCount` and `registeredBufferSize` (both consulted
 * by [STATIC] only).
 */
public enum class RegisteredBufferStrategy {

    /**
     * Fixed Buffer support is disabled. Every zero-copy send goes through
     * regular `IORING_OP_SEND_ZC` with per-send page pinning, and every
     * `indexOf` lookup returns `-1` so the write dispatch automatically
     * picks the unregistered path.
     *
     * Use when:
     * - `RLIMIT_MEMLOCK` is tight (containers without `CAP_IPC_LOCK`,
     *   tiny ulimit caps) and the per-process registration footprint of
     *   [STATIC] would exceed it.
     * - The running kernel does not support
     *   `IORING_REGISTER_BUFFERS` (< Linux 5.6) — [STATIC] auto-falls
     *   back to this value with a warn log.
     * - Profiling shows the Fixed Buffer benefit is not worth the
     *   startup memory pin (small message sizes, short-lived
     *   connections).
     */
    DISABLED,

    /**
     * The default. Pre-allocate N buffer slots at startup and
     * `io_uring_register_buffers` them in one shot, lifetime fixed
     * until engine close. `SEND_ZC_FIXED` dispatch is automatic — the
     * write path looks up the buffer pointer and uses `SEND_ZC_FIXED`
     * when the ptr lands inside a registered slot, otherwise falls
     * through to regular `SEND_ZC` (the same per-write lookup that
     * shipped pre-strategy).
     *
     * Slot count and slot size are configurable via
     * `registeredBufferSlotCount` and `registeredBufferSize` on the
     * engine constructor. Both are per-EventLoop; the total
     * `RLIMIT_MEMLOCK` footprint is
     * `registeredBufferSlotCount × registeredBufferSize × eventLoopCount`.
     *
     * Requires Linux ≥ 5.6 (`IORING_REGISTER_BUFFERS`). If the kernel
     * lacks the capability the engine logs a warn and falls back to
     * [DISABLED]; the engine still starts.
     */
    STATIC,

    /**
     * Sparse-register slots at startup, then bind / unbind chunks via
     * `IORING_REGISTER_BUFFERS_UPDATE` as the allocator's chunk lifecycle
     * progresses. Targets long-lived workloads (TLS / WebSocket
     * sessions) where the working set churns chunks faster than [STATIC]
     * can accommodate within `RLIMIT_MEMLOCK`. Requires Linux ≥ 6.0 and
     * the chunk-based allocator's reclaim hooks.
     *
     * **Not yet implemented**. Selecting this value fails fast at engine
     * init with a clear error message. Planned alongside the chunk-based
     * allocator's reclaim-policy work, whose chunk lifecycle hooks the
     * dynamic bind/unbind depends on.
     */
    DYNAMIC,
}
