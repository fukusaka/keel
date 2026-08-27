package io.github.fukusaka.keel.native.readiness

/**
 * A connection-lifetime member of one EventLoop: something the loop must tell
 * when it stops, whether or not it happens to hold a registration at that
 * moment.
 *
 * This is deliberately not part of [FdReadyListener]. That interface is the
 * *readiness* SPI — its callbacks are per-registration events, carry the
 * [Interest] the listener acts on, and exist only while an entry sits in a
 * ledger. Stopping is a *lifecycle* event: it happens once per participant, it
 * carries no interest because nothing acts on one (when the loop is gone, every
 * registration the participant holds on it is gone with it), and it must reach
 * a participant that holds no registration at all — a paused connection whose
 * one-shot entry was consumed is the ordinary case, not the exotic one, because
 * keel's own flow control pauses reads at a high watermark. Keying the
 * notification on the ledger missed exactly that participant.
 *
 * **Membership is explicit.** A transport joins through
 * [AbstractReadinessEventLoop.joinLoop] when its channel attaches — not when it
 * is built — and removes itself in its teardown; registering a readiness callback does not imply
 * membership, and the accept arms of the pipelined servers never join — a
 * server's own `close()` already works on a stopped loop through its
 * `ifStopped` fallback.
 */
interface LoopParticipant {

    /**
     * The EventLoop this participant belongs to has stopped and will not
     * dispatch again.
     *
     * Called exactly once per participant while the loop takes itself apart, on
     * the loop thread, after the registry has released it — so a participant may
     * register with another loop from here. Registering with *this* one is
     * refused: the ledgers and the registry were closed in the same step that
     * emptied them, so the attempt neither appends nor arms. It is logged and
     * not signalled back — a second call to this method would lead straight
     * here again.
     *
     * This is not [FdReadyListener.onPeerClosed]: the peer may be perfectly
     * healthy. What ended is the loop's ability to report readiness, which for
     * anything waiting on this connection is the same practical outcome and a
     * different cause.
     */
    fun onLoopStopped()

    /**
     * The registration this participant joined with was withdrawn before it
     * ever fired, because the kernel refused its arm.
     *
     * Only for an arm issued from off the loop, where the failure has no
     * caller frame to return into: [AbstractReadinessEventLoop.joinLoop]
     * answers an on-loop refusal by returning `false` instead. Called once,
     * on the loop thread, after the registry has released this participant.
     *
     * This is not [onLoopStopped]: the loop is running and will keep
     * dispatching for everyone else. What ended is this participant's own
     * ability to hear anything — an arm is the only thing that delivers an
     * event, so a withdrawn one leaves a connection that cannot hear its
     * peer, not even its close.
     *
     * **The default only reports.** Delegating to [onLoopStopped] would tell
     * a participant the loop is gone, and one that answers that by moving to
     * another loop or by treating the engine as dead would be acting on
     * something untrue. Reporting is the most any participant can be assumed
     * to want; one that can do something about it — ending the connection
     * this registration was for — overrides this.
     */
    fun onInitialArmRefused(cause: Throwable) = Unit
}
