package io.github.fukusaka.keel.engine.iouring

import io_uring.IORING_OP_SEND_ZC
import io_uring.IORING_OP_SENDMSG_ZC
import io_uring.io_uring
import io_uring.keel_get_probe_ring
import io_uring.keel_opcode_supported
import io_uring.keel_probe_free
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * Runtime-detected io_uring kernel capabilities.
 *
 * Controls which io_uring features are used by the engine. Features
 * that are not available on the running kernel are automatically
 * disabled, and the engine falls back to simpler alternatives:
 *
 * | Feature | Fallback |
 * |---------|----------|
 * | multishot accept | single-shot accept (one SQE per accept) |
 * | multishot recv + provided buffer ring | pull-model read via Channel.read |
 * | SEND_ZC | regular IORING_OP_SEND (IoMode.CQE) |
 *
 * **Detection strategy**:
 * - Opcode probe (liburing `io_uring_get_probe_ring`): for opcodes like SEND_ZC
 * - Kernel version (`uname`): for features that are opcode flags (multishot)
 *   or APIs (provided buffer ring) not detectable via opcode probe
 *
 * **User override**: pass a custom [IoUringCapabilities] to [IoUringEngine]
 * to force-enable or force-disable features (e.g., for testing or debugging).
 *
 * ```
 * // Auto-detect (default)
 * IoUringEngine()
 *
 * // Disable SEND_ZC
 * IoUringEngine(capabilities = IoUringCapabilities.detect(ring).copy(sendZc = false))
 *
 * // Force all features off (minimal mode)
 * IoUringEngine(capabilities = IoUringCapabilities.MINIMAL)
 * ```
 */
@OptIn(ExperimentalForeignApi::class)
data class IoUringCapabilities(
    /** Multishot accept (Linux 5.19+). One SQE → multiple accept CQEs. */
    val multishotAccept: Boolean = true,
    /** Multishot recv (Linux 6.0+). One SQE → multiple recv CQEs with provided buffers. */
    val multishotRecv: Boolean = true,
    /** Provided buffer ring (Linux 5.19+). Kernel-managed buffer selection. */
    val providedBufferRing: Boolean = true,
    /** Fixed file descriptors (Linux 5.1+). Per-SQE fd lookup elimination. */
    val fixedFiles: Boolean = true,
    /**
     * Cooperative task run (Linux 6.0+). Run task_work only on io_uring_enter
     * calls (not on every syscall return). Eliminates IPIs for task_work.
     *
     * Safe for keel: [IoUringEventLoop.loop] blocks in io_uring_submit_and_wait
     * every iteration, so task_work is always drained promptly.
     *
     * Loopback A/B benchmarks showed no measurable effect (<1% within
     * run-to-run variance). The IPI-reduction benefit is theoretically visible
     * only on real NICs with multi-core contention — default-on captures that
     * benefit without harm on loopback.
     */
    val coopTaskrun: Boolean = true,
    /**
     * Single-issuer ring (Linux 6.0+). Hints — and, on Linux 6.2+, enforces —
     * that all `io_uring_register_*` calls and `io_uring_enter` submissions
     * come from a single pthread (the owning EventLoop). Eliminates
     * kernel-side locking on the submission path.
     *
     * Safe for keel post-PR #276: `io_uring_queue_init` runs in
     * `IoUringEventLoop.loop()` on the EL pthread; every `io_uring_register_*`
     * call is routed via `initOnEventLoop()` / `onExitHook` /
     * `withContext(workerLoop)` / fire-and-forget dispatch onto the same
     * pthread. Thread affinity is asserted by
     * [IoUringEventLoop.assertInEventLoop].
     *
     * A/B benchmark on loopback (4t/100c /hello) showed no measurable
     * effect (<1% within run-to-run variance). The kernel-side
     * lock-elimination benefit is theoretically larger on high-contention
     * workloads with multi-core submit/complete interleaving. Default-on.
     */
    val singleIssuer: Boolean = true,
    /**
     * Deferred task run (Linux 6.1+). Defers task_work execution to
     * `io_uring_enter(GETEVENTS)` calls. Requires [singleIssuer].
     *
     * The classic task_work path runs deferred completion work at the end
     * of any syscall returning to user space. DEFER_TASKRUN instead only
     * runs task_work when the issuer thread actively calls `io_uring_enter`
     * with `GETEVENTS` — which is exactly what [IoUringEventLoop.loop] does
     * every iteration via `io_uring_submit_and_wait`. The benefit is
     * reduced latency variability (no task_work interruptions during EL
     * `drainTasks` or SQE preparation) and better batching of completions.
     *
     * Compatible with keel's eventfd-based wakeup: when an external thread
     * writes to the eventfd, the READ op's completion is queued as task_work,
     * which the EL pthread processes inside its already-blocked
     * `io_uring_submit_and_wait` call.
     *
     * **Default: false** (opt-in). Loopback A/B benchmark
     * (4t/100c /hello) showed mixed results:
     * - Throughput: -0.9% (870K → 862K req/s)
     * - p99 latency: -3.2% (373us → 361us, **improvement**)
     *
     * The throughput regression is at the variance boundary. Enable for
     * latency-sensitive deployments where tail latency stability matters
     * more than raw RPS. Tracking the trade-off by workload is recommended.
     *
     * Requires SINGLE_ISSUER: enabling DEFER without SI is a kernel
     * configuration error (`-EINVAL`). Rely on [detect] to keep the pair
     * consistent.
     */
    val deferTaskrun: Boolean = false,
    /**
     * Registered buffers for SEND_ZC_FIXED (Linux 5.1+).
     *
     * Pre-pins pooled buffer pages via `io_uring_register_buffers`,
     * eliminating per-send page pinning in SEND_ZC. Only effective
     * when [sendZc] is also enabled.
     *
     * **Default: false** (opt-in). Benefit is primarily on real NICs
     * with DMA; loopback shows +7.9% on /large (100KB).
     *
     * **Memory overhead** when enabled:
     * - Userspace: pool warmup fills all slots (8 KiB × 8 = 64 KiB / EventLoop).
     *   Buffers are pooled, not additional memory.
     * - Kernel: page table entries for pinned pages (~16 pages × 4 KiB
     *   = 64 KiB pinned / EventLoop for default 8 KiB × 8 slot pool).
     * - HashMap: address→index mapping (~512 bytes / EventLoop).
     * - Total for 4 EventLoops: ~256 KiB kernel pinned + ~2 KiB HashMap.
     */
    val registeredBuffers: Boolean = false,
    /**
     * Cross-EventLoop wakeup via `IORING_OP_MSG_RING` (Linux 5.18+).
     *
     * When a coroutine dispatched from **another keel EventLoop pthread**
     * needs to wake a peer EventLoop, the source EL submits a MSG_RING SQE
     * on its own ring targeting the peer's ring fd, instead of writing to
     * the peer's eventfd. The kernel synthesises a CQE on the target ring
     * with a keel-assigned `user_data` token, and the peer's
     * `io_uring_submit_and_wait` returns without an `eventfd_write` syscall
     * on the source side.
     *
     * **Hybrid policy**: wakeups from threads that are *not* running on a
     * keel EventLoop pthread (external callers such as `engine.close()`,
     * `Dispatchers.Default`, or application threads) continue to use
     * `eventfd_write`. MSG_RING only optimises EL-to-EL dispatch because
     * external threads do not own an io_uring ring to submit SQEs on.
     *
     * Integrates with [deferTaskrun]: the target CQE is queued as task_work
     * and processed inside the peer's next `io_uring_submit_and_wait`,
     * aligning the wakeup path with DEFER_TASKRUN's batching semantics.
     *
     * **Default: false** (opt-in). Benefit depends on how often coroutine
     * dispatches cross EventLoops in the workload. Loopback `/hello` with
     * one connection per EventLoop shows near-zero cross-EL traffic and no
     * measurable change. Workloads that fan out work across EventLoops
     * (coroutine channels, codec handoff) are expected to benefit.
     *
     * **Fallbacks** when this capability is true but a wakeup cannot use
     * MSG_RING:
     * - Source SQ ring full — falls back to `eventfd_write` for this wakeup.
     * - Caller is not on any EL pthread — eventfd path as before.
     * - Target ring is closed — the source CQE reports `-EBADF`; the wakeup
     *   is redundant because the target is already shutting down.
     */
    val msgRingWakeup: Boolean = false,
    /**
     * Self-register the ring's own file descriptor (Linux 5.18+).
     *
     * Calls `io_uring_register_ring_fd` on the EventLoop pthread after
     * [IoUringEventLoop] initialises the kernel ring. Subsequent
     * `io_uring_enter` syscalls — issued implicitly by
     * `io_uring_submit_and_wait` on every loop iteration — take the
     * `IORING_ENTER_REGISTERED_RING` fast path, and the kernel skips
     * the per-syscall file-descriptor table lookup on the ring fd.
     *
     * **Default: true** (auto-enabled on kernel 5.18+). No API change,
     * no memory overhead, independent of `singleIssuer` /
     * `deferTaskrun`. The register call runs on the EL pthread via the
     * existing 2-phase init pattern, so SINGLE_ISSUER remains
     * compatible.
     *
     * Remote A/B on a LAN pair (pipeline-http-io-uring, 4t/100c /hello,
     * wrk on a separate client host) showed **+5.3 % throughput** with
     * the keel default (`singleIssuer = true`) and **+9.0 %** with
     * `singleIssuer = false`. The optimisation helps across SI settings
     * on realistic network workloads.
     *
     * Loopback A/B on the same server showed **-2.6 %** — the CPU-bound
     * hot path pays the `register_ring_fd` / `unregister_ring_fd`
     * bookkeeping cost without amortising the fd-lookup savings,
     * because per-enter work is already sub-microsecond on loopback.
     * Keel targets real-network workloads so the default stays on,
     * but benchmark loops that pin themselves to loopback may want to
     * override via `detect(ring).copy(registerRingFd = false)`.
     *
     * Register failures are warn-logged and the EventLoop continues on
     * the slow path — the optimisation is best-effort.
     *
     * Teardown: paired `io_uring_unregister_ring_fd` runs in
     * [IoUringEventLoop.loop]'s epilogue, after the register-class
     * `onExitHook` and before `io_uring_queue_exit`. The explicit
     * unregister is strictly not required (`queue_exit` cleans up the
     * ring's internal state) but keeps register/unregister symmetric
     * and surfaces any kernel-side breakage as a warn-level log.
     */
    val registerRingFd: Boolean = true,
    /** Zero-copy send (Linux 6.0+). Two CQEs per operation. */
    val sendZc: Boolean = true,
    /**
     * Zero-copy sendmsg (Linux 6.1+). Gather write + zero-copy. Two CQEs per operation.
     *
     * Implies [sendZc]: if sendmsgZc is true, sendZc must also be true because
     * [IoMode.SENDMSG_ZC] falls back to SEND_ZC for single-buffer flushes.
     * [detect] enforces this invariant. Manual [copy] callers must not set
     * `sendmsgZc = true, sendZc = false`.
     */
    val sendmsgZc: Boolean = true,
) {
    companion object {
        /**
         * Auto-detect capabilities from the running kernel.
         *
         * Uses opcode probe for features detectable at opcode level (SEND_ZC),
         * and kernel version for features that are opcode flags or APIs
         * (multishot, provided buffer ring).
         *
         * @param ring An initialised io_uring ring (used for opcode probing).
         */
        fun detect(ring: CPointer<io_uring>): IoUringCapabilities {
            val kv = KernelVersion.current()
            val probe = keel_get_probe_ring(ring)

            val sendZcSupported = probe != null && keel_opcode_supported(probe, IORING_OP_SEND_ZC) != 0
            val sendmsgZcSupported = probe != null && keel_opcode_supported(probe, IORING_OP_SENDMSG_ZC) != 0
            val caps = IoUringCapabilities(
                multishotAccept = kv >= KernelVersion(5, 19),
                multishotRecv = kv >= KernelVersion(6, 0),
                providedBufferRing = kv >= KernelVersion(5, 19),
                fixedFiles = kv >= KernelVersion(5, 1),
                coopTaskrun = kv >= KernelVersion(6, 0),
                singleIssuer = kv >= KernelVersion(6, 0),
                // deferTaskrun is opt-in even on supported kernels. Loopback
                // A/B showed a slight throughput regression with a p99 latency
                // improvement. Users enable via `detect(ring).copy(deferTaskrun = true)`.
                deferTaskrun = false,
                // msgRingWakeup is opt-in even on supported kernels. Benefit
                // depends on cross-EL dispatch volume; users enable via
                // `detect(ring).copy(msgRingWakeup = true)` after measuring
                // their workload. Kernel support is not probed here because
                // the flag is never auto-enabled — a manual override on a
                // kernel lacking IORING_OP_MSG_RING will surface as -EINVAL
                // from the kernel on the first MSG_RING SQE submission.
                msgRingWakeup = false,
                // registerRingFd auto-enables on kernel 5.18+. Real-network
                // A/B (wrk from a separate host over LAN) showed +5.3 %
                // throughput with SINGLE_ISSUER on and +9.0 % with it off;
                // loopback A/B showed -2.6 % because per-enter work on
                // loopback is too cheap to amortise the register bookkeeping.
                // keel targets real-network workloads, so the default is on
                // and loopback benchmarks should opt out explicitly if they
                // require the loopback-optimal path.
                registerRingFd = kv >= KernelVersion(5, 18),
                // sendmsgZc implies sendZc (6.1+ kernel has both opcodes).
                sendZc = sendZcSupported || sendmsgZcSupported,
                sendmsgZc = sendmsgZcSupported,
            )

            if (probe != null) keel_probe_free(probe)
            return caps
        }

        /** Minimal capabilities: all advanced features disabled. */
        val MINIMAL = IoUringCapabilities(
            multishotAccept = false,
            multishotRecv = false,
            providedBufferRing = false,
            fixedFiles = false,
            coopTaskrun = false,
            singleIssuer = false,
            deferTaskrun = false,
            msgRingWakeup = false,
            registerRingFd = false,
            sendZc = false,
            sendmsgZc = false,
        )
    }
}
