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
     */
    fun onReady(interest: Interest)

    /**
     * Peer FIN or RST was observed for this fd. Default no-op — only listeners
     * that surface peer-close to higher layers override it.
     *
     * For a combined data-and-EOF event this is called *after* [onReady], so
     * the listener can drain the final bytes first; for a pure EOF only this is
     * called. The engine unconditionally disarms the fd's interest afterwards,
     * so the listener does not need to disarm explicitly.
     */
    fun onPeerClosed(interest: Interest) {}
}
