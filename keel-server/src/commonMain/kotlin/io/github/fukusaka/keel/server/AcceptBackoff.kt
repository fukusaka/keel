package io.github.fukusaka.keel.server

/**
 * Accept error backoff strategy used by [acceptLoopWithBackoff].
 *
 * When `StreamServer.accept()` fails (e.g. EMFILE — too many open files),
 * the accept loop pauses before retrying to avoid CPU spin on persistent
 * errors. The delay resets to the initial value on a successful accept
 * (relevant only for [Exponential]).
 */
public sealed class AcceptBackoff {
    /**
     * Constant delay between accept retries.
     *
     * @param delayMs delay in milliseconds (default: 100ms).
     */
    public data class Fixed(val delayMs: Long = DEFAULT_INITIAL_DELAY_MS) : AcceptBackoff()

    /**
     * Exponential backoff: doubles the delay on each consecutive failure
     * and resets to [initialMs] on the next successful accept.
     *
     * @param initialMs initial delay in milliseconds (default: 100ms).
     * @param maxMs maximum delay in milliseconds (default: 1000ms).
     */
    public data class Exponential(
        val initialMs: Long = DEFAULT_INITIAL_DELAY_MS,
        val maxMs: Long = DEFAULT_MAX_DELAY_MS,
    ) : AcceptBackoff()

    public companion object {
        public const val DEFAULT_INITIAL_DELAY_MS: Long = 100L
        public const val DEFAULT_MAX_DELAY_MS: Long = 1000L
    }
}
