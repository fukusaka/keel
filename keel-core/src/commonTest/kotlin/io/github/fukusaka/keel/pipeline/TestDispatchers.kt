package io.github.fukusaka.keel.pipeline

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlin.coroutines.CoroutineContext

/**
 * Dispatcher doubles for the two answers a loop can give the pipeline.
 *
 * Shared rather than repeated: the pipeline routes work onto its owning
 * context, so every test of that routing needs a way to say "and it ran" or
 * "and it never did", and a second copy of these in the same package is a
 * redeclaration rather than a convenience.
 */

/**
 * Runs a routed block before `dispatch` returns.
 *
 * Lets a test assert on the effect of routed work without a real loop, which
 * is what makes the *decision to route* the thing being asserted.
 */
internal object RunImmediately : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable): Unit = block.run()
}

/**
 * Accepts every dispatch and runs none of them — a stopped loop's queue.
 *
 * Needed wherever [RunImmediately] would hide the defect by running the very
 * block a dead loop never would.
 */
internal object NeverRuns : CoroutineDispatcher() {
    override fun dispatch(context: CoroutineContext, block: Runnable): Unit = Unit
}
