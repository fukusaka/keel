package io.github.fukusaka.keel.native.readiness

import kotlin.concurrent.AtomicInt

/**
 * A group of readiness event loops, distributing connections across threads.
 *
 * The two POSIX engines had this class twice, differing only in the loop type
 * and in how each builds one. What is shared is everything that happens *to* a
 * group once its loops exist: round-robin hand-out, all-or-none start, and the
 * two rollback paths that give back what was already built when construction or
 * start fails part way.
 *
 * **Construction stays with the subclass.** Its loops need engine-specific
 * arguments, and an abstract factory called from this constructor would run
 * before the subclass's own fields were initialised. [buildLoops] is offered
 * instead: the subclass calls it with a lambda that builds one loop, and gets
 * back an array or a failure with everything already built given back.
 *
 * @param loops the loops this group owns, already built.
 */
@OptIn(InternalReadinessEngineApi::class)
@InternalReadinessEngineApi
abstract class AbstractReadinessEventLoopGroup<L : AbstractReadinessEventLoop>(
    private val loops: Array<L>,
) {

    private val index = AtomicInt(0)

    /** Number of event loops in this group. */
    val size: Int get() = loops.size

    /**
     * Starts every loop's thread.
     *
     * All or none. `pthread_create` fails with `EAGAIN` when the process is out
     * of threads, and a partial start would leave this group's earlier loops
     * running and its later ones idle, with the group reference discarded by
     * the constructor that threw — nothing left to stop them or to give back
     * what they hold. On failure every loop is closed, whether it was started
     * or not: closing a started one joins its thread, and closing an unstarted
     * one runs the teardown its thread would have.
     */
    fun start() {
        try {
            for (loop in loops) loop.start()
        } catch (startFailure: Throwable) {
            closeAll(loops.asList(), startFailure)
            throw startFailure
        }
    }

    /**
     * Returns the next loop in round-robin order.
     *
     * Atomic increment with overflow-safe masking. Thread-safe: several accept
     * threads may call this at once.
     */
    fun next(): L {
        val i = (index.getAndIncrement() and Int.MAX_VALUE) % loops.size
        return loops[i]
    }

    /** Returns the loop at [index], bypassing the round-robin. */
    fun at(index: Int): L = loops[index]

    /** Whether any loop in this group still holds a callback for [fd] + [interest]. */
    @InternalReadinessEngineApi
    fun hasCallbackRegistration(fd: Int, interest: Interest): Boolean =
        loops.any { it.hasCallbackRegistration(fd, interest) }

    /** Total participants across this group's loops. */
    @InternalReadinessEngineApi
    fun participants(): Int = loops.sumOf { it.participantCount() }

    /**
     * Stops every loop's thread and releases what the group holds.
     *
     * Every loop is closed whatever the ones before it did. A loop's `close()`
     * can throw — its teardown re-raises what its stages failed with — and
     * walking out on the first would leave the rest of the group holding their
     * descriptors with no second caller to try again. The first failure is what
     * the caller is told; the others are attached to it.
     */
    fun close() {
        var failure: Throwable? = null
        for (loop in loops) {
            try {
                loop.close()
            } catch (closeFailure: Throwable) {
                val first = failure
                if (first == null) failure = closeFailure else first.addSuppressed(closeFailure)
            }
        }
        failure?.let { throw it }
    }

    public companion object {
        /**
         * Builds [size] loops one at a time, giving back what was built if one
         * of them fails.
         *
         * `Array(size) { … }` cannot do this: a constructor that throws on loop
         * k discards the array along with loops 0..k-1, and those are fully
         * built — each holding a readiness descriptor, a wakeup primitive,
         * native scratch and an allocator child that only its own `close()`
         * returns. Nothing else has a reference to them, so nothing ever will.
         */
        public inline fun <reified L : AbstractReadinessEventLoop> buildLoops(
            size: Int,
            create: (Int) -> L,
        ): Array<L> {
            val built = ArrayList<L>(size)
            try {
                repeat(size) { built += create(it) }
            } catch (constructionFailure: Throwable) {
                closeAll(built, constructionFailure)
                throw constructionFailure
            }
            return built.toTypedArray()
        }

        /**
         * Closes [toRelease], attaching any failure to [cause] rather than
         * letting it replace the reason the rollback is happening.
         *
         * The caller is owed the failure that ended construction or the start,
         * not one from the cleanup after it.
         */
        @PublishedApi
        internal fun closeAll(toRelease: List<AbstractReadinessEventLoop>, cause: Throwable) {
            for (loop in toRelease) {
                try {
                    loop.close()
                } catch (closeFailure: Throwable) {
                    cause.addSuppressed(closeFailure)
                }
            }
        }
    }
}
