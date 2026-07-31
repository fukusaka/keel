package io.github.fukusaka.keel.native.posix

/**
 * Listener for fd readiness on the pipeline (non-suspend) path.
 *
 * Implemented by the transports and servers that call `registerCallback`, so
 * the receiver can pass `this` as the listener — no per-call lambda allocation
 * on the read re-arm fast path. The [Interest] parameter lets one
 * implementation handle read and write callbacks without separate sub-listener
 * objects.
 *
 * The two callbacks separate normal readiness from peer-close detection, and a
 * listener that only cares about one side leaves the other as the default
 * no-op: a pipelined server overrides only [onReady] (its fd teardown is driven
 * by `close()`, not by peer FIN), while a transport overrides both, because the
 * peer-close path is what fires `onReadClosed` to user code even when the user
 * never enabled reads (a write-only push client with `readEnabled = false`).
 * That connection reaches this callback because both transports arm READ at
 * construction regardless of `readEnabled`, not because the callback works
 * without an arm.
 */
interface FdReadyListener {
    /**
     * Ready for [interest]: data available (READ), space available (WRITE),
     * accept queue non-empty (server fd READ).
     *
     * Called on **every** dispatch for this fd, including one carrying only a
     * peer-close signal with no data behind it. A listener therefore sees the
     * end of the stream the ordinary way — its `read()` returns 0 — and does
     * not need to distinguish that case from a normal wakeup.
     */
    fun onReady(interest: Interest)

    /**
     * Peer FIN or RST was observed for this fd. Default no-op — only listeners
     * that surface peer-close to higher layers override it.
     *
     * Always called *after* [onReady], so the listener can drain whatever data
     * arrived alongside the close first. It exists for the case a `read()`
     * cannot cover: a listener that never reads, for which this is the only way
     * a peer close reaches user code.
     *
     * **It only reaches a listener that is still registered.** Registrations are
     * one-shot: a listener that declines to re-arm — the back-pressure case,
     * where it does not want the data that woke it — gives up peer-close
     * detection along with it, because the interest carrying the close is the
     * one being dropped. A listener that is never woken at all keeps its
     * registration and is covered for the connection's lifetime; one that is
     * woken once and declines is not, until it arms again.
     *
     * The engine takes the interest back afterwards **only if nothing on that
     * `(fd, interest)` still wants it** — neither a callback re-registered from
     * here or from [onReady], nor a suspend waiter queued on the same key. It
     * used to disarm unconditionally; that discarded a live registration and
     * left an accept loop that never ran again.
     */
    fun onPeerClosed(interest: Interest) {}

    /**
     * The EventLoop this registration was made with has stopped and will not
     * dispatch again. Default no-op.
     *
     * Called once per surviving registration while the loop takes itself apart,
     * on the loop thread, after the ledger has released it — so a listener may
     * register with another loop from here. Registering with *this* one is
     * refused: the ledgers were closed in the same step that emptied them, so
     * the attempt neither appends nor arms. It is logged and not signalled back
     * — a second call to this method would lead straight here again.
     *
     * This is not [onPeerClosed]: the peer may be perfectly healthy. What ended
     * is the loop's ability to report readiness, which for a listener waiting on
     * one is the same practical outcome and a different cause. A listener whose
     * teardown is driven from elsewhere -- a server closed by `close()` rather
     * than by readiness -- leaves it as the no-op.
     *
     * **No [Interest], unlike its two siblings.** They carry one because a
     * listener acts on it: [onReady] dispatches read against write, and
     * [onPeerClosed] returns early on anything but READ. Nothing acts on it here
     * — when the loop is gone, every registration a listener holds on it is gone
     * with it, so which one this call stands for changes no decision. Carrying it
     * anyway cost a keyed walk over the ledger and a public accessor on
     * [io.github.fukusaka.keel.collections.LongObjectMap] to support it, for a
     * value no implementation read.
     */
    fun onLoopStopped() {}
}
