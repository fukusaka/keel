package io.github.fukusaka.keel.engine.nwconnection

import kotlinx.cinterop.ExperimentalForeignApi
import platform.darwin.DISPATCH_QUEUE_CONCURRENT
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_async
import platform.darwin.dispatch_group_create
import platform.darwin.dispatch_group_enter
import platform.darwin.dispatch_group_leave
import platform.darwin.dispatch_group_wait
import platform.darwin.dispatch_queue_create
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Synthetic verification: does Kotlin/Native honour Grand Central Dispatch's
 * happens-before guarantees for non-atomic, non-volatile field accesses across
 * [dispatch_async] block boundaries on a serial queue?
 *
 * **Background.** Apple documents that all blocks submitted to the same serial
 * `dispatch_queue_t` run with happens-before ordering: writes performed in
 * block N are visible to block N+1, even if the two blocks execute on
 * different worker threads (GCD migrates blocks across its worker thread
 * pool but inserts the necessary memory barriers at the dispatch boundary).
 * Swift's memory model recognises this guarantee at the language level,
 * which is how SwiftNIO Transport Services can use ordinary mutable state
 * on its `NIOTSEventLoop` `DispatchQueue` without `Atomic*` wrappers for
 * every field.
 *
 * **K/N grey area.** Kotlin/Native's public memory-model documentation
 * (`kotlinlang.org/docs/native-memory-manager.html`) does not state whether
 * the K/N compiler / LLVM backend honours this happens-before relationship.
 * In theory, `dispatch_async` is an opaque function call to LLVM and the
 * optimiser cannot hoist memory loads across it, so the guarantee should
 * "fall out" of standard compiler behaviour — but this has never been
 * verified for K/N.
 *
 * If non-atomic field writes turn out NOT to be visible to a subsequent
 * block on the same serial queue, then the "SwiftNIO-style" engine model
 * (queue identity + plain mutable state) cannot be used safely from K/N
 * and every field touched across a `dispatch_async` boundary needs to be
 * `@Volatile` (or wrapped in an `Atomic*`).
 *
 * **Methodology.** Each test runs a sentinel-style A/B stress loop:
 * - A "writer" block on the queue assigns a known monotonically-increasing
 *   value into a plain (non-atomic, non-volatile) `Int` field.
 * - A "reader" block on the **same** queue reads the field and verifies it
 *   sees the expected value.
 * - The total iteration count is large enough (≥ 100 000) that the GCD
 *   worker thread pool will routinely migrate the queue across CPUs.
 * - Any mismatch (read value < expected write value) indicates either a
 *   compiler-level reordering / hoist or a missing memory fence at the
 *   dispatch boundary — i.e. K/N is **not** honouring GCD happens-before.
 *
 * The mismatch counter itself is `AtomicInt` so we can observe it from
 * the asserting thread without confounding the experiment.
 *
 * **Expected outcome.** If K/N honours GCD HB (the optimistic case the
 * GCD cross-worker refcount investigation is trying to confirm), the mismatch count should be
 * exactly 0 across all iterations. Any non-zero result is direct evidence
 * that NWConnection-based engines need explicit `@Volatile` / `AtomicInt`
 * coverage on every cross-block-visible field.
 *
 * **Conclusion (verified).** K/N honours GCD happens-before on a serial queue:
 * the serial-queue tests observe 0 mismatches and the concurrent positive
 * control fires, confirming the SwiftNIO-style "queue identity + plain mutable
 * state" model is safe from Kotlin/Native. The question this PoC answers is
 * settled — hence the `@Ignore` below (kept for on-demand re-verification, not
 * run per-CI).
 */
// CI-demoted (2026-07-14): each test floods GCD with 100-200K dispatch_async block
// pairs and then blocks on dispatch_group_wait(FOREVER). On a loaded shared macOS
// CI runner the drain stalls until the ~25min job timeout — a raw native wait that
// Kotlin's withTimeout cannot interrupt. Observed hanging ~22min on a CI macos-latest
// runner during an unrelated PR, then passing on re-run; local runs drain in seconds.
// This is one-time Kotlin/Native memory-model verification (conclusion above), not a
// per-CI regression guard, so it is ignored out of the gate. Re-run locally on demand:
//   ./gradlew :keel-engine-nwconnection:macosArm64Test --tests '*PocGcdHappensBeforeTest'
@Ignore
@OptIn(ExperimentalForeignApi::class, ExperimentalAtomicApi::class)
class PocGcdHappensBeforeTest {

    /**
     * Holder for the "shared state under test". Deliberately uses a plain
     * `var` — no `@Volatile`, no `Atomic*`, no `synchronized {}` — to expose
     * whatever the K/N compiler emits for ordinary field stores/loads.
     */
    private class Holder {
        var value: Int = -1
    }

    /**
     * Baseline: a single producer + consumer pair on a serial queue. The
     * writer block sets `holder.value = i`, then the very next block (also
     * on the queue) reads it. If GCD HB is honoured we expect 0 mismatches.
     */
    @Test
    fun `serial queue write then read sees latest value`() {
        val queue = dispatch_queue_create("keel.poc.gcd-hb.serial", null)
        val holder = Holder()
        val mismatches = AtomicInt(0)
        val iterations = 200_000

        val group = dispatch_group_create()
        repeat(iterations) { i ->
            dispatch_group_enter(group)
            dispatch_async(queue) {
                holder.value = i
            }
            dispatch_async(queue) {
                val read = holder.value
                if (read != i) {
                    mismatches.fetchAndAdd(1)
                }
                dispatch_group_leave(group)
            }
        }

        dispatch_group_wait(group, DISPATCH_TIME_FOREVER)

        val observed = mismatches.load()
        assertEquals(
            expected = 0,
            actual = observed,
            message = "K/N appears NOT to honour GCD happens-before on a serial queue: " +
                "$observed/$iterations cross-block reads observed a stale " +
                "non-atomic Int field. Means SwiftNIO-style 'queue identity + plain mutable " +
                "state' is unsafe; every cross-callback field needs @Volatile or Atomic*.",
        )
    }

    /**
     * Tighter loop: many dispatch hops per iteration to encourage worker
     * thread migration. GCD will reuse the same worker thread when load is
     * light; submitting bursts increases the chance the queue picks up a
     * different worker between writer and reader.
     */
    @Test
    fun `serial queue burst submission still sees latest value`() {
        val queue = dispatch_queue_create("keel.poc.gcd-hb.burst", null)
        val holder = Holder()
        val mismatches = AtomicInt(0)
        val iterations = 100_000

        val group = dispatch_group_create()
        // Submit all writer+reader pairs up front in a tight loop on the
        // calling thread, then wait once. GCD has to drain the entire batch
        // serially while juggling worker threads under churn.
        repeat(iterations) { i ->
            dispatch_group_enter(group)
            dispatch_async(queue) {
                holder.value = i
            }
            dispatch_async(queue) {
                val read = holder.value
                if (read != i) {
                    mismatches.fetchAndAdd(1)
                }
                dispatch_group_leave(group)
            }
        }

        dispatch_group_wait(group, DISPATCH_TIME_FOREVER)

        val observed = mismatches.load()
        assertEquals(
            expected = 0,
            actual = observed,
            message = "Burst-mode mismatches on serial queue: $observed/$iterations",
        )
    }

    /**
     * Positive control: a **concurrent** queue must NOT provide HB between
     * blocks (Apple's documented contract). If this test does not observe
     * mismatches at all, the test methodology is too weak to detect races
     * (e.g. the harness is so slow that races simply don't materialise) and
     * the absence of mismatches in the serial-queue tests above proves
     * nothing.
     *
     * We expect this test to observe `mismatches > 0`; we do NOT assert that
     * the serial-queue tests are correct unless this positive control fires.
     */
    @Test
    fun `concurrent queue does NOT provide cross-block ordering positive control`() {
        // DISPATCH_QUEUE_CONCURRENT: blocks can run in parallel on multiple
        // worker threads with no HB between them. This is the deliberate
        // opposite of the contract we're testing for the serial case.
        val queue = dispatch_queue_create("keel.poc.gcd-hb.concurrent", DISPATCH_QUEUE_CONCURRENT)
        val holder = Holder()
        val mismatches = AtomicInt(0)
        val iterations = 100_000

        val group = dispatch_group_create()
        repeat(iterations) { i ->
            dispatch_group_enter(group)
            dispatch_async(queue) {
                holder.value = i
            }
            dispatch_async(queue) {
                val read = holder.value
                // On a concurrent queue, reader may run before writer or
                // observe a stale value. Any divergence from `i` counts.
                if (read != i) {
                    mismatches.fetchAndAdd(1)
                }
                dispatch_group_leave(group)
            }
        }

        dispatch_group_wait(group, DISPATCH_TIME_FOREVER)

        val observed = mismatches.load()
        // We deliberately do NOT assertEquals(0, …). We assert that the
        // harness IS capable of observing races; if the count were 0, the
        // experiment would have no power.
        check(observed > 0) {
            "Positive control failed: harness observed 0 races on a concurrent " +
                "queue across $iterations iterations. The serial-queue results " +
                "above are therefore inconclusive — methodology cannot detect a race."
        }
        // For visibility in test output:
        val pctTimes100 = observed * 10_000L / iterations
        println(
            "[positive-control] concurrent-queue mismatches = $observed / $iterations " +
                "(${pctTimes100 / 100}.${(pctTimes100 % 100).toString().padStart(2, '0')}%)",
        )
    }
}
