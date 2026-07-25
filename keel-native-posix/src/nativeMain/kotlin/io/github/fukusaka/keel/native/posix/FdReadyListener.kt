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
 * peer-close path is what fires `onReadClosed` to user code even when read
 * interest was never armed (a write-only push client with `readEnabled = false`).
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
     * The engine unconditionally disarms the fd's interest afterwards, so the
     * listener does not need to disarm explicitly.
     */
    fun onPeerClosed(interest: Interest) {}
}
