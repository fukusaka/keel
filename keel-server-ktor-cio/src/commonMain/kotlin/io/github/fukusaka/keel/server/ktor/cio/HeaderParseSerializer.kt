package io.github.fukusaka.keel.server.ktor.cio

/**
 * Serialises calls to ktor-http-cio's `parseRequest` / `parseHttpBody` so
 * concurrent header-parse calls do not contend on
 * [ktor-utils-io](https://github.com/ktorio/ktor/tree/main/ktor-io)'s
 * shared `HeadersDataPool` lock.
 *
 * **Why this exists**: On Kotlin/Native, ktor-http-cio's `parseHeaders`
 * calls `HeadersDataPool.borrow()`, which acquires a `SynchronizedObject`
 * lock and, while holding it, invokes `clearInstance(item)`.  The
 * `HeadersData.release()` path called from `clearInstance` re-enters
 * `HeadersDataPool.recycle()` / `borrow()` against the same lock.
 *
 * On the JVM `synchronized` is reentrant and biased / JIT-optimised, so
 * the recursive acquisition is essentially free even under heavy
 * concurrency.  On Native the lock is a non-biased `pthread_mutex` and
 * many parallel callers (e.g. one I/O worker per CPU core) contend
 * pathologically — a 100-connection accept burst can collapse to ≈ 0
 * RPS for the entire 10 s benchmark window.
 *
 * Empirically (macOS M1, `KqueueEngine` default workers ≈ 12 cores,
 * wrk 4t/100c/10s, 20 iterations, accept-burst protocol):
 *
 * | Configuration                                | failures (0 RPS) | median RPS | p99      |
 * | ---                                          | ---              | ---        | ---      |
 * | parallel parsers (no serialisation)          | 6 / 20           | ≈ 14 500   | ≈ 11 ms  |
 * | single worker (`threads=1`)                  | 0 / 20           | ≈ 36 000   | ≈ 5.3 ms |
 * | parallel I/O + serialised parser (this class)| 0 / 20           | ≈ 43 400   | ≈ 2.8 ms |
 *
 * Native applies a process-wide [kotlinx.coroutines.sync.Mutex] around
 * every `parseRequest` invocation in this adapter; `parseHttpBody` is
 * intentionally not serialised because body decoding is per-connection
 * (no shared pool) and may run for unbounded durations on streaming
 * uploads.  JVM uses a no-op pass-through (`synchronized` is reentrant
 * + JIT-optimised).  See [KtorCioConnectionHandler] for the call site.
 *
 * **Trade-off**: serialised parsing caps single-host parser throughput
 * at the single-core parse rate, but I/O work (accepts, reads, writes,
 * body parsing) still parallelises across all workers.  In practice
 * this beats `threads=1` because the parser is fast (~µs) and the
 * remaining work runs concurrently.  If you need higher per-host
 * throughput than ≈ 43 k RPS and can drop ktor-http-cio's parser, the
 * keel-native HTTP codec (`pipeline-http-*` engines via
 * `addHttp1ServerCodec` from `:keel-codec-http`) parses on the I/O thread
 * without this lock and reaches > 150 k RPS on the same hardware.
 *
 * **Upstream**: tracked at the ktor issue tracker — link to be added when
 * filed.  When the upstream `HeadersDataPool` is reworked to release the
 * lock around `clearInstance`, this class can become a no-op on every
 * platform and eventually be deleted.
 */
internal expect class HeaderParseSerializer() {
    /** Runs [block] under the platform-specific serialisation policy. */
    suspend fun <T> withLock(block: suspend () -> T): T
}
