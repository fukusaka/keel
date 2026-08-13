package io.github.fukusaka.keel.native.readiness

/**
 * What [LoopHandoff.runOnLoop] did with the work.
 *
 * Separate from a plain "did the wait run out" because the caller has two
 * questions and they are not the same one: whether it still owns the
 * descriptor (it does on both fallback outcomes) and whether the ordering
 * the wait exists to provide was actually obtained.
 */
enum class HandoffOutcome {
    /**
     * The loop has the work — it ran inline on the loop's own thread, is
     * queued for a live loop, or claimed it out of the final drain before
     * this caller could.
     */
    HANDED_TO_LOOP,

    /** The loop was quiet, or went quiet while this caller waited; the fallback ran. */
    FELL_BACK,

    /**
     * The fallback ran because the wait budget ran out, so it went without
     * the ordering the wait provides — a queued arm on that loop may still
     * name a descriptor number this caller has since released.
     */
    FELL_BACK_AFTER_EXPIRY,
}
