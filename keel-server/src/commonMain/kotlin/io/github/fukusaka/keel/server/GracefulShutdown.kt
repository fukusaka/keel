package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.core.IoEngine
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Two-phase graceful shutdown for keel-engine-backed servers.
 *
 * 1. **Grace phase** (up to [gracePeriodMillis]): signal stop via
 *    [stopRequest], then wait for [serverJob] (the bind / accept
 *    coordinator) to finish and for in-flight per-connection handlers
 *    — children of [engine] — to drain naturally. Per-connection
 *    handlers must be launched on the engine scope (not on [serverJob])
 *    so they survive [serverJob.join] and are observable here as
 *    `engine.coroutineContext.job.children`.
 * 2. **Force phase** (remaining of [timeoutMillis]): if the grace phase
 *    timed out, cancel [serverJob] and wait briefly for it to return.
 * 3. **Engine close** (always, in `finally`): [IoEngine.close] cancels
 *    every coroutine launched on the engine scope and joins their
 *    completion before tearing the dispatcher threads down. This closes
 *    the structured-concurrency contract at the engine boundary even if
 *    the grace timeout was hit above — provided this call is left to
 *    finish. The join answers to *this* coroutine, so cancelling the
 *    shutdown gives it up; what each engine does with the rest of its
 *    teardown then is written on [IoEngine.close]. Note also that this
 *    step is outside both budgets above, so [timeoutMillis] does not
 *    bound it.
 *
 * Designed for HTTP-style servers where every accepted connection runs
 * its handler on the engine scope and stop is requested by completing a
 * shared signal job ([stopRequest]) that the accept loop monitors.
 *
 * @param serverJob the bind / accept coordinator job, the parent of the
 *   accept loops only — handlers are intentionally not its children.
 * @param stopRequest signal job completed by this helper to wake the
 *   accept coordinator.
 * @param engine the I/O engine; its scope owns the per-connection handlers.
 * @param gracePeriodMillis maximum wait for natural drain.
 * @param timeoutMillis hard upper bound; the force phase gets the
 *   remainder after the grace phase.
 */
public suspend fun gracefulShutdown(
    serverJob: Job,
    stopRequest: CompletableJob,
    engine: IoEngine,
    gracePeriodMillis: Long,
    timeoutMillis: Long,
) {
    try {
        stopRequest.complete()
        val drained = withTimeoutOrNull(gracePeriodMillis) {
            serverJob.join()
            engine.coroutineContext.job.children.toList().forEach { it.join() }
            true
        }
        if (drained == null) {
            serverJob.cancel()
            withTimeoutOrNull(timeoutMillis - gracePeriodMillis) {
                serverJob.join()
            }
        }
    } finally {
        engine.close()
    }
}
