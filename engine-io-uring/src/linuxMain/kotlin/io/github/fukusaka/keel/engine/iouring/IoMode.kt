package io.github.fukusaka.keel.engine.iouring

/**
 * I/O operation mode for io_uring channels.
 *
 * Controls whether individual I/O operations (read/write) use the
 * io_uring SQE/CQE completion path or attempt a direct POSIX syscall
 * first with CQE as a fallback.
 *
 * @see IoModeSelector for runtime mode selection
 * @see IoModeSelectors for built-in selection strategies
 */
enum class IoMode {
    /**
     * Submit all I/O via io_uring SQE and await CQE completion.
     *
     * This is the traditional io_uring path. Suitable for high-concurrency
     * workloads where SQE batching amortises submission overhead.
     */
    CQE,

    /**
     * Attempt a direct POSIX syscall (`send()`/`recv()`) first.
     * If the syscall returns EAGAIN (socket buffer full / no data),
     * fall back to io_uring SQE/CQE for the remainder.
     *
     * For small responses (e.g., HTTP /hello), the direct syscall
     * completes immediately without SQE/CQE round-trip overhead.
     * Measured +4.5% throughput on /hello (13B response).
     */
    FALLBACK_CQE,

    /**
     * Zero-copy send via `IORING_OP_SEND_ZC` (Linux 6.0+).
     *
     * The kernel sends data directly from user-space memory without
     * copying to the socket buffer. This produces TWO CQEs per operation:
     * 1. Send result (bytes sent, flags may include `IORING_CQE_F_MORE`)
     * 2. Buffer release notification (kernel done with user buffer)
     *
     * The buffer MUST NOT be freed until the second CQE arrives.
     * [submitSendZc][IoUringEventLoop] handles this by suspending
     * until the notification CQE completes.
     *
     * **Limitations in the current keel architecture**:
     * - Small payloads (< 64KB): the two-CQE round-trip overhead and
     *   kernel page-table pinning cost exceed the memcpy savings.
     *   Measured -7.8% on /hello (13B) vs [FALLBACK_CQE].
     * - Loopback: no NIC DMA occurs, so the zero-copy benefit
     *   (avoiding kernel→NIC copy) does not apply.
     * - BufferedSuspendSink flushes in 8KB chunks, limiting per-send
     *   size. SEND_ZC benefits are proportional to data size.
     * - Gather write uses [IoMode.CQE] (not SEND_ZC). Scatter/gather
     *   zero-copy requires `IORING_OP_SENDMSG_ZC` (not implemented).
     *
     * **When SEND_ZC may help**: real network (non-loopback) with large
     * single-buffer sends (e.g., sendfile-style static file serving).
     * Not applicable to typical HTTP response patterns in keel.
     *
     * This mode is available for manual selection via
     * [IoModeSelectors.SEND_ZC] but is NOT included in adaptive
     * strategies (no automatic switching based on [ConnectionStats]).
     */
    SEND_ZC,
}
