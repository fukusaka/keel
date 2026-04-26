package io.github.fukusaka.keel.server

import io.github.fukusaka.keel.core.Channel
import io.github.fukusaka.keel.core.StreamServer
import io.github.fukusaka.keel.logging.Logger
import io.github.fukusaka.keel.logging.error
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Drives [StreamServer.accept] in a loop with [AcceptBackoff] applied to
 * persistent failures.
 *
 * Per accept the helper invokes [onAccept] with the new [Channel]. The
 * caller is responsible for launching the per-connection handler on the
 * appropriate scope / dispatcher (typically the engine's
 * [io.github.fukusaka.keel.core.IoEngine] scope and the channel's
 * [Channel.ioDispatcher]) so handlers participate in structured
 * concurrency with the engine and run on the I/O thread.
 *
 * The loop terminates when the server stops accepting
 * ([StreamServer.isActive] becomes false) or the calling coroutine is
 * cancelled. [CancellationException] is rethrown.
 *
 * On accept failure the loop logs the error and waits according to
 * [backoff], then retries. Successful accept resets [AcceptBackoff.Exponential]
 * to its initial delay.
 */
public suspend fun StreamServer.acceptLoopWithBackoff(
    backoff: AcceptBackoff = AcceptBackoff.Exponential(),
    logger: Logger,
    onAccept: (Channel) -> Unit,
) {
    var currentDelayMs = when (backoff) {
        is AcceptBackoff.Fixed -> backoff.delayMs
        is AcceptBackoff.Exponential -> backoff.initialMs
    }

    while (isActive) {
        try {
            val channel = accept()
            currentDelayMs = when (backoff) {
                is AcceptBackoff.Fixed -> backoff.delayMs
                is AcceptBackoff.Exponential -> backoff.initialMs
            }
            onAccept(channel)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!isActive) break
            logger.error(e) { "accept failed, retrying in ${currentDelayMs}ms" }
            delay(currentDelayMs)
            if (backoff is AcceptBackoff.Exponential) {
                currentDelayMs = (currentDelayMs * 2).coerceAtMost(backoff.maxMs)
            }
        }
    }
}
