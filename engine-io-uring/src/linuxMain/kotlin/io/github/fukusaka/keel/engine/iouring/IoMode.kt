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
}
