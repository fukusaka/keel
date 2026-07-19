#!/usr/bin/env bash
# Benchmark a single server with k6 (streaming endpoints — /upload-stream / /sse-stream)
#
# Mirrors bench-one.sh's contract (start server, run client, parse output,
# emit `<name>|<rps>|<p50>|<p99>` row) but drives k6 instead of wrk so the
# request-body / response-body streaming paths can be exercised.
#
# Usage: ./benchmark/bench-stream-one.sh <name> <scenario> <command> [args...]
#
# scenario:
#   upload        POST /upload-stream  (request-body streaming throughput)
#   xthread       POST /xthread        (cross-thread chunk-release A/B vs upload)
#   sse           GET  /sse-stream     (response-body streaming throughput)
#   ws-echo       GET  /ws-echo        (WebSocket echo throughput, small frames)
#   ws-large      GET  /ws-echo        (single-VU large-frame round-trip throughput
#                                       via ws-large.js, default 1 MB binary
#                                       payload — exercises the server's ability
#                                       to deliver a single message bigger than
#                                       the kernel send buffer)
#   ws-fragment   GET  /ws-echo        (RFC 6455 fragmented-frame send + reassembly
#                                       echo bench via the custom Go client at
#                                       benchmark/wsbench/. k6 cannot construct
#                                       fragmented frames, so this scenario uses
#                                       a Go binary instead — built on demand
#                                       when missing or cross-platform-broken
#                                       if `go` is on PATH. Opt out of the
#                                       auto-rebuild with
#                                       BENCH_WSBENCH_AUTOBUILD=false and pre-
#                                       build manually with
#                                       `cd benchmark/wsbench && go build`)
#   ws-deflate    GET  /ws-deflate     (permessage-deflate echo bench via the
#                                       custom Go client at benchmark/wsbench/.
#                                       gorilla negotiates RFC 7692 (k6 cannot),
#                                       echoes a synthetic compressible payload.
#                                       This loopback run is a functional smoke
#                                       test only — the compression win needs a
#                                       bandwidth-capped real network, see
#                                       bench-remote-ws.sh. Same wsbench
#                                       auto-build plumbing as ws-fragment.)
#
# Environment variables (HTTP-level):
#   BENCH_RUNS                    Number of runs; median is reported (default: 1)
#   BENCH_COOLDOWN                Seconds between runs (default: 2)
#   BENCH_K6_SUCCESS_THRESHOLD    Minimum checks_succeeded percentage to accept
#                                 a run as valid (default: 95). Lower values
#                                 are reported as `checks=NN.NN%` instead of
#                                 a phantom RPS number — protects against
#                                 servers that respond fast but corruptly
#                                 (e.g. a chunked-encoder bug that fails
#                                 99.98% of SSE body-size checks).
#   BENCH_TEMP_CAPTURE            1 = append `temp=START->ENDC(dN)` (CPU temp at
#                                 the start/end of the measured window) to the
#                                 result row. No sudo / no install (see
#                                 bench-temp.sh). Default 0.
#
# Environment variables forwarded to k6 (script-specific defaults apply):
#   BENCH_K6_VUS            k6 virtual users          (default: 50)
#   BENCH_K6_DURATION       k6 bench duration         (default: 15s)
#   BENCH_K6_TIMEOUT        max wall-clock for one k6 invocation (default: 90s).
#                            Wraps each `k6 run` / `wsbench` invocation in
#                            `timeout(1)` so a stuck client (e.g. k6/websockets
#                            VU deadlocked on `ws.send`) cannot wedge the
#                            bench-stream-all chain indefinitely. Default is
#                            `K6_DURATION + ~75s buffer` to cover graceful-stop
#                            (k6 default `gracefulStop=30s`) + VU startup
#                            warmup. Timeouts mark the row as `TIMEOUT` and
#                            store the raw output with a `[TIMEOUT exit=124|137]`
#                            marker so the bench chain proceeds to the next
#                            engine.
#   BENCH_K6_KILL_AFTER     grace period between SIGTERM and SIGKILL when
#                            BENCH_K6_TIMEOUT fires (default: 30s). k6/
#                            websockets has been observed to ignore SIGTERM
#                            when its VU goroutine is parked inside a Go
#                            `net.Conn.Write` syscall — without this grace
#                            escalation the `timeout` parent waits forever
#                            for a non-responsive child. SIGKILL is uncatchable
#                            and guarantees release.
#   BENCH_PAYLOAD_KB        upload.js payload size KB (default: 64)
#   BENCH_UPLOAD_BYTES      upload.js payload size bytes (overrides
#                            BENCH_PAYLOAD_KB if set; accepts MB-scale,
#                            e.g. 10485760 = 10 MB)
#   BENCH_UPLOAD_PATH       upload.js target route (default: /upload-stream;
#                            the `xthread` scenario sets it to /xthread)
#   BENCH_SSE_COUNT         sse.js frame count        (default: 100)
#   BENCH_SSE_SIZE          sse.js per-frame bytes    (default: 1024)
#   BENCH_WS_PAYLOAD        ws-echo.js msg size bytes (default: 256)
#   BENCH_WS_PING_PONGS     msgs per VU before close  (default: unlimited until duration)
#   BENCH_WS_TYPE           ws-echo.js payload type   (text | binary, default: text)
#   BENCH_WS_CLOSE_HANDSHAKE ws-echo.js initiate WS close handshake at end of
#                            session instead of TCP close (true | false,
#                            default: false)
#   BENCH_WS_LARGE_BYTES    ws-large.js single-message payload bytes
#                            (default: 1048576 = 1 MB)
#   BENCH_WS_FRAG_BYTES     wsbench single-message payload bytes for the
#                            ws-fragment / ws-deflate scenarios
#                            (default: 4096)
#   BENCH_WS_FRAG_COUNT     ws-fragment.go frame count per message
#                            (default: 4)
#   BENCH_WS_COMPRESSION    ws-deflate scenario: negotiate permessage-deflate
#                            (true | false, default: true). Forwarded to
#                            wsbench as `-compression=`; set false for the
#                            uncompressed A/B leg.
#   BENCH_MULTIPART_PARTS   multipart.js part count   (default: 5)
#   BENCH_MULTIPART_PART_BYTES  multipart.js per-part bytes (default: 4096)
#   BENCH_METHODS           method-mix.js comma-list of HTTP methods to
#                            rotate (default: GET,POST,PUT,DELETE,PATCH,OPTIONS;
#                            HEAD excluded by default — pipeline-http does not
#                            auto-strip body for HEAD responses)
#   BENCH_PATH_ID_RANGE     path-param.js modulus for the path id
#                            (default: 100; set to 1 to disable rotation)
#   BENCH_SLOW_INTERVAL_MS  slow-upload.js per-iteration sleep ms
#                            (default: 100, 0 = behave like upload.js)
#   BENCH_WS_BURST_PINGS     ws-slow-consumer.js up-front burst size
#                            (default: 16)
#   BENCH_WS_CONSUME_DELAY_MS  ws-slow-consumer.js per-echo sleep ms
#                            (default: 50)
#   BENCH_COMPRESSION_TYPE  compression.js Accept-Encoding header value
#                            (default: "gzip"; "br" / "deflate" / "identity"
#                            also accepted; sent verbatim to the wire)
#   BENCH_WSBENCH_AUTOBUILD   when "true" (default), the ws-fragment scenario
#                              auto-rebuilds the `benchmark/wsbench/wsbench`
#                              binary via `(cd benchmark/wsbench && go build)`
#                              if the binary is missing or fails a `--help`
#                              probe (e.g. Mach-O binary rsync'd onto a Linux
#                              bench host). Set to "false" on CI / read-only
#                              filesystems where you would rather fail than
#                              rebuild.
#   BENCH_COMPRESSION_ENABLE  when "true", append `--compression=true` to the
#                              server start command so the engine emits a
#                              compressed response. Off by default —
#                              preserves the historical /hello + /large
#                              uncompressed baselines for non-`compression`
#                              scenarios. Engines that don't support
#                              server-side compression (`pipeline-http-*`,
#                              Native `ktor-keel-*`) ignore the flag.
#   BENCH_COMPRESSION_STRICT  when "false", the `compression` scenario
#                              also scores engines that returned uncompressed
#                              responses (Content-Encoding absent) — useful
#                              for a single leaderboard mixing compression-on
#                              vs compression-missing engines. Default "true":
#                              engines that do not compress FAIL the run so
#                              the leaderboard is not polluted with /large
#                              throughput masquerading as compression numbers.
#   BENCH_COMPRESSION_UPLOAD_STRICT
#                              `compression-upload` scenario counterpart of
#                              BENCH_COMPRESSION_STRICT. Default "true":
#                              engines that do not decompress request bodies
#                              FAIL the run (X-Bytes-Received reports the
#                              compressed size instead of the decoded size).
#                              Set "false" for throughput comparisons across
#                              engines that do not yet decompress.
#   BENCH_JFR                 when "true" and the engine command is a JVM
#                              run (the first arg is `java` or ends in `/java`),
#                              prepend `-XX:StartFlightRecording=settings=…,
#                              filename=…,dumponexit=true` so a `.jfr` file is
#                              written alongside the raw bench output. The
#                              recording covers the full JVM lifetime (server
#                              startup + k6 load + cooldown), making it the
#                              right tool for hot-path / heap allocation /
#                              lock contention analysis after the run.
#                              No-op (with stderr warning) for Native bench
#                              binaries — Kotlin/Native has no JFR equivalent.
#   BENCH_JFR_SETTINGS        JFR profile preset, passed through verbatim
#                              as `settings=…`. Default "profile" (richer
#                              method sampling than the built-in "default").
#                              Set to a path for a custom .jfc file.
#   BENCH_GC_LOG              when "true" and the engine command is a JVM
#                              run, prepend `-Xlog:gc*:file=…:tags,uptime,
#                              time,level` so a `.gc.log` is written
#                              alongside the bench output. Useful for
#                              cross-engine GC pressure comparison
#                              (request-body aggregator vs streaming etc.).
#                              No-op for Native bench binaries.
#   BENCH_HTTP_CONNECTION_CLOSE   when "true", forward `CONNECTION_CLOSE=true`
#                            to upload.js / sse.js so every HTTP request
#                            carries `Connection: close` and the TCP socket
#                            is torn down per request. Default off (HTTP/1.1
#                            keep-alive). Used by
#                            `bench-keepalive-compare.sh` to A/B-test how
#                            much of the throughput comes from connection
#                            reuse vs the per-request handler path.
#                            Has no effect on WS scenarios (the upgrade
#                            owns the connection lifecycle).
#   BENCH_SCHEME            "http" or "https" (default: http). Forwarded
#                            to k6 scripts as `SCHEME=...` (HTTP scenarios)
#                            and `WS_SCHEME=ws|wss` (WS scenarios). The
#                            harness's READY check uses the same scheme.
#                            HTTPS requires the server to be started with
#                            `--tls=jsse|openssl|mbedtls|awslc` (engine
#                            command-line flag); the bench cert is the
#                            shared self-signed one from
#                            `BenchmarkCertificates`, so k6 also runs with
#                            `insecureSkipTLSVerify: true`.
#
# Example:
#   ./benchmark/bench-stream-one.sh ktor-keel-nio upload \
#       benchmark/build/bin/.../benchmark.kexe --engine=ktor-keel-nio --port=18090
#   BENCH_PAYLOAD_KB=256 ./benchmark/bench-stream-one.sh ktor-cio upload \
#       java -cp ... io.github.fukusaka.keel.benchmark.JvmMainKt --engine=ktor-cio --port=18090

set -uo pipefail

NAME="${1:?Usage: bench-stream-one.sh <name> <scenario> <command> [args...]}"
SCENARIO="${2:?Usage: bench-stream-one.sh <name> <scenario> <command> [args...]}"
shift 2

# Map scenario name to k6 script + endpoint hint (for readiness probe) +
# parser kind (HTTP req metrics vs WebSocket session/msg metrics).
case "$SCENARIO" in
    upload)
        SCRIPT="benchmark/k6/upload.js"
        READY_ENDPOINT="/hello"
        PARSER="http"
        ;;
    xthread)
        # Same drain loop as `upload` but targets /xthread, where the server
        # releases each pooled chunk off the EventLoop (cross-thread return).
        # Pairs with `upload` (EventLoop release) for an A/B of the allocator's
        # cross-thread return path. Reuses upload.js via UPLOAD_PATH.
        SCRIPT="benchmark/k6/upload.js"
        READY_ENDPOINT="/hello"
        PARSER="http"
        BENCH_UPLOAD_PATH="${BENCH_UPLOAD_PATH:-/xthread}"
        ;;
    sse)
        SCRIPT="benchmark/k6/sse.js"
        READY_ENDPOINT="/hello"
        PARSER="http"
        ;;
    multipart)
        SCRIPT="benchmark/k6/multipart.js"
        READY_ENDPOINT="/hello"
        PARSER="http"
        ;;
    method-mix)
        SCRIPT="benchmark/k6/method-mix.js"
        READY_ENDPOINT="/hello"
        PARSER="http"
        ;;
    path-param)
        SCRIPT="benchmark/k6/path-param.js"
        READY_ENDPOINT="/hello"
        PARSER="http"
        ;;
    compression)
        # Reuses /large under Accept-Encoding so the wire payload comes
        # straight from the engine's response-compression path. Server-side
        # compression is opt-in via `BENCH_COMPRESSION_ENABLE=true`
        # (forwarded as `--compression=true` to the server start command —
        # see bench-stream-one.sh's CMD assembly below).
        SCRIPT="benchmark/k6/compression.js"
        READY_ENDPOINT="/hello"
        PARSER="http"
        ;;
    compression-upload)
        # Inverse of `compression`: client gzips the request body and
        # POSTs to /upload-stream with `Content-Encoding: gzip`. Verifies
        # the **inbound** decompression path (k6 sends compressed bytes,
        # server should report the decoded byte count via
        # `X-Bytes-Received`).
        #
        # Defaults `BENCH_COMPRESSION_ENABLE=true` so the server starts
        # with `--compression=true` and installs the inbound decode path
        # (KeelCompression plugin / HttpRequestDecompressionHandler).
        # Without this default the server runs decompression-disabled,
        # the route handler sees raw gzip bytes, and the strict check
        # (`reportedBytes === EXPECTED_DECODED_BYTES`) fails uniformly
        # across all engines — masking real engine behaviour as a
        # blanket "everything FAIL" signal. Override with
        # `BENCH_COMPRESSION_ENABLE=false` to measure throughput against
        # engines that do not decompress yet (combine with
        # `BENCH_COMPRESSION_UPLOAD_STRICT=false`).
        SCRIPT="benchmark/k6/compression-upload.js"
        READY_ENDPOINT="/hello"
        PARSER="http"
        BENCH_COMPRESSION_ENABLE="${BENCH_COMPRESSION_ENABLE:-true}"
        ;;
    slow-upload)
        SCRIPT="benchmark/k6/slow-upload.js"
        READY_ENDPOINT="/hello"
        PARSER="http"
        ;;
    ws-slow-consumer)
        SCRIPT="benchmark/k6/ws-slow-consumer.js"
        READY_ENDPOINT="/hello"
        PARSER="ws"
        ;;
    ws-echo)
        SCRIPT="benchmark/k6/ws-echo.js"
        READY_ENDPOINT="/hello"
        PARSER="ws"
        ;;
    ws-large)
        SCRIPT="benchmark/k6/ws-large.js"
        READY_ENDPOINT="/hello"
        PARSER="ws"
        ;;
    ws-held)
        # Held-pooled workload for the allocator-capability measurement. The
        # /ws-held/:n/:mode route holds n pooled payloads per connection before
        # echoing the evicted oldest; run the server with --profile-alloc to
        # capture the central carve under that held working set. Route ring size
        # + mode come from BENCH_WS_PATH (default /ws-held/64/chunks).
        SCRIPT="benchmark/k6/ws-held.js"
        READY_ENDPOINT="/hello"
        PARSER="ws"
        # Binary frames are the ones delivered as pooled BinaryChunks (the path
        # the measurement exercises); default to binary unless overridden.
        BENCH_WS_TYPE="${BENCH_WS_TYPE:-binary}"
        ;;
    ws-fragment)
        # The Go-based wsbench client constructs RFC 6455 fragmented
        # frames (k6's k6/ws cannot). It already emits the
        # `<name>|<rps>|<p50>|<p99>` row format directly so the k6
        # parser path is bypassed.
        SCRIPT="benchmark/wsbench/wsbench"
        READY_ENDPOINT="/hello"
        PARSER="wsbench"
        ;;
    ws-deflate)
        # The Go-based wsbench client negotiates RFC 7692 permessage-
        # deflate (k6's k6/websockets cannot). Same wsbench plumbing as
        # ws-fragment — the `wsbench` parser path is used; the bench is
        # driven with `-scenario=deflate` below.
        SCRIPT="benchmark/wsbench/wsbench"
        READY_ENDPOINT="/hello"
        PARSER="wsbench"
        ;;
    *)
        echo "Unknown scenario: $SCENARIO (expected: upload|xthread|sse|multipart|method-mix|path-param|compression|compression-upload|slow-upload|ws-slow-consumer|ws-echo|ws-large|ws-held|ws-fragment|ws-deflate)" >&2
        exit 1
        ;;
esac

PORT=18090
RUNS=${BENCH_RUNS:-1}
COOLDOWN=${BENCH_COOLDOWN:-2}
READY_TIMEOUT=${BENCH_READY_TIMEOUT:-60}
# Optional CPU-temperature capture (BENCH_TEMP_CAPTURE=1) — see bench-temp.sh.
TEMP_CAPTURE=${BENCH_TEMP_CAPTURE:-0}
# shellcheck source=benchmark/bench-temp.sh
. "$(dirname "$0")/bench-temp.sh"
K6_VUS=${BENCH_K6_VUS:-50}
K6_DURATION=${BENCH_K6_DURATION:-15s}
K6_TIMEOUT=${BENCH_K6_TIMEOUT:-90s}
# Grace period between SIGTERM and SIGKILL. GNU `timeout` defaults to SIGTERM
# only; if the child ignores SIGTERM (k6/websockets has been observed to
# park inside a Go `net.Conn.Write` syscall that does not honour the
# scheduler-level cancel) the parent `timeout` waits forever. `-k Ns`
# escalates to SIGKILL after the grace, guaranteeing wall-clock release.
K6_KILL_AFTER=${BENCH_K6_KILL_AFTER:-30s}
if ! command -v timeout >/dev/null 2>&1; then
    echo "warning: 'timeout' command not found; k6 invocation will not be wall-clock protected." >&2
    echo "         install GNU coreutils ('brew install coreutils' on macOS) to enable BENCH_K6_TIMEOUT." >&2
    TIMEOUT_BIN=""
else
    TIMEOUT_BIN="timeout"
fi

# Wrap a command in `timeout` if available. Used to bound `k6 run` and the
# Go `wsbench` invocation so a stuck client (e.g. k6/websockets VU deadlocked
# on `ws.send`) cannot wedge the bench-stream-all chain indefinitely. The
# function is callable from inside `$(...)` subshells (bash inherits parent
# function definitions). When TIMEOUT_BIN is empty the function falls
# through to the bare command (no wall-clock protection).
#
# `-k ${K6_KILL_AFTER}`: SIGTERM at $K6_TIMEOUT, escalate to SIGKILL after
# $K6_KILL_AFTER additional grace. Required because k6/websockets ignores
# SIGTERM when stuck in a Go runtime syscall and would otherwise pin the
# `timeout` parent indefinitely.
run_k6_with_timeout() {
    if [ -n "$TIMEOUT_BIN" ]; then
        "$TIMEOUT_BIN" -k "$K6_KILL_AFTER" "$K6_TIMEOUT" "$@"
    else
        "$@"
    fi
}
SCHEME=${BENCH_SCHEME:-http}
case "$SCHEME" in
    http)  WS_SCHEME=ws  ;;
    https) WS_SCHEME=wss ;;
    *)
        echo "error: BENCH_SCHEME must be 'http' or 'https' (got '$SCHEME')." >&2
        exit 2
        ;;
esac

# Save raw k6 output alongside wrk results so summaries can be recreated
# from log evidence rather than re-running everything. Mirrors the
# directory layout used by `bench-keel.sh` / `bench-all.sh`.
RESULTS_BASE="benchmark/results"
HOST_LABEL="${BENCH_HOST_LABEL:-$(hostname -s)}"
RESULTS_DIR="${RESULTS_BASE}/${HOST_LABEL}"
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
mkdir -p "$RESULTS_DIR"

# Extract --port=N from args if present
for arg in "$@"; do
    case "$arg" in
        --port=*) PORT="${arg#--port=}" ;;
    esac
done

# When compression enabled, append `--compression=true` to the server
# launch command. The benchmark CLI parser ignores duplicates, so this
# is safe even if the caller already passed --compression=... — last
# wins per BenchmarkConfig.parse() semantics.
COMPRESSION_ENABLE="${BENCH_COMPRESSION_ENABLE:-false}"
if [ "$COMPRESSION_ENABLE" = "true" ]; then
    set -- "$@" --compression=true
fi

# JFR + GC observability injection. Detect JVM command (first arg is
# `java` or ends in `/java`); for non-JVM commands (Native `.kexe`),
# emit a warning and continue without flags. The Native warning is
# stderr-only so the bench's stdout row format stays clean.
SAFE_NAME=$(printf '%s' "$NAME" | tr -c 'A-Za-z0-9._-' '-')
JFR_REQUESTED="${BENCH_JFR:-false}"
GC_LOG_REQUESTED="${BENCH_GC_LOG:-false}"
JFR_FILE=""
GC_LOG_FILE=""
if [ "$JFR_REQUESTED" = "true" ] || [ "$GC_LOG_REQUESTED" = "true" ]; then
    CMD_FIRST="$1"
    CMD_FIRST_BASENAME="${CMD_FIRST##*/}"
    if [ "$CMD_FIRST" = "java" ] || [ "$CMD_FIRST_BASENAME" = "java" ]; then
        JVM_OPTS=()
        if [ "$JFR_REQUESTED" = "true" ]; then
            JFR_FILE="${RESULTS_DIR}/${SAFE_NAME}-${SCENARIO}-${TIMESTAMP}.jfr"
            JFR_SETTINGS="${BENCH_JFR_SETTINGS:-profile}"
            # `dumponexit=true` writes the .jfr only when the JVM exits;
            # combined with the harness's SIGTERM teardown after the k6
            # run, this captures the full bench lifetime.
            JVM_OPTS+=("-XX:StartFlightRecording=settings=${JFR_SETTINGS},filename=${JFR_FILE},dumponexit=true")
        fi
        if [ "$GC_LOG_REQUESTED" = "true" ]; then
            GC_LOG_FILE="${RESULTS_DIR}/${SAFE_NAME}-${SCENARIO}-${TIMESTAMP}.gc.log"
            JVM_OPTS+=("-Xlog:gc*:file=${GC_LOG_FILE}:tags,uptime,time,level")
        fi
        # Inject JVM_OPTS between $1 (java) and $2... (-cp / class / args).
        # `set --` rebuilds the positional params so the eventual
        # `setsid "$@"` call below picks them up unchanged.
        set -- "$1" "${JVM_OPTS[@]}" "${@:2}"
    else
        printf 'warning: BENCH_JFR / BENCH_GC_LOG requested but command "%s" is not a JVM run; skipping\n' "$CMD_FIRST" >&2
    fi
fi

if [ "$PARSER" = "wsbench" ]; then
    # Custom Go client; require pre-built binary to keep this script
    # ecosystem-free at runtime (matches the rust-bench / go-bench /
    # swift-bench / zig-bench convention).
    #
    # The wsbench binary is .gitignore'd and platform-specific (Go output
    # is Mach-O on macOS, ELF on Linux). When the repo is rsync'd from a
    # development host to the bench host (e.g. macOS -> Linux), the
    # source-side Mach-O overwrites whatever was last built on the
    # destination host and `./wsbench` aborts with `Exec format error`
    # at the kernel exec stage. Historically this silently produced
    # empty cells in the ws-fragment bench table because the failed exec
    # message was captured as the bench "result" (and the parser found
    # no rps number in it).
    #
    # Probe the binary with `--help` (Go `flag` exits 0): if the probe
    # fails and the Go toolchain is on PATH, rebuild for the current
    # platform automatically. Otherwise emit a clear message pointing
    # at the rebuild command. `BENCH_WSBENCH_AUTOBUILD=false` opts out
    # of the auto-rebuild (CI / read-only filesystems).
    if [ ! -x "$SCRIPT" ] || ! "$SCRIPT" --help >/dev/null 2>&1; then
        if [ ! -x "$SCRIPT" ]; then
            echo "wsbench binary not built." >&2
        else
            echo "wsbench binary cannot execute on this host (likely cross-platform mismatch — Mach-O vs ELF from rsync transfer)." >&2
        fi
        if [ "${BENCH_WSBENCH_AUTOBUILD:-true}" = "true" ] && command -v go >/dev/null 2>&1; then
            echo "Rebuilding wsbench for this platform with 'cd benchmark/wsbench && go build'..." >&2
            # Remove the stale binary first; `go build` refuses to overwrite
            # a non-object-file at the output path (covers the rsync-overwrite
            # case where the existing file is e.g. Mach-O on a Linux host).
            rm -f "$SCRIPT"
            (cd benchmark/wsbench && go build) || { echo "wsbench rebuild failed" >&2; exit 1; }
            # Re-probe after rebuild to confirm the binary now executes.
            if ! "$SCRIPT" --help >/dev/null 2>&1; then
                echo "wsbench rebuild produced a binary but the probe still failed. Inspect '$SCRIPT'." >&2
                exit 1
            fi
        else
            echo "Rebuild with: cd benchmark/wsbench && go build" >&2
            exit 1
        fi
    fi
elif ! command -v k6 >/dev/null 2>&1; then
    echo "k6 not installed (see benchmark/k6/README.md)" >&2
    exit 1
fi

# --- Port management (reused from bench-one.sh) ---

kill_port() {
    local port="$1"
    local pids
    if [ "$(uname)" = "Linux" ] && command -v fuser >/dev/null 2>&1; then
        pids=$(fuser "$port"/tcp 2>/dev/null) || return 0
    elif command -v lsof >/dev/null 2>&1; then
        pids=$(lsof -ti :"$port" 2>/dev/null) || return 0
    else
        return 0
    fi
    [ -z "$pids" ] && return 0
    kill $pids 2>/dev/null || return 0
    for _ in $(seq 1 20); do
        kill -0 $pids 2>/dev/null || return 0
        sleep 0.1
    done
    kill -9 $pids 2>/dev/null || true
}

median() {
    echo "$@" | tr ' ' '\n' | sort -n | awk '{a[NR]=$1} END {
        if (NR%2==1) print a[(NR+1)/2]
        else printf "%.2f\n", (a[NR/2]+a[NR/2+1])/2
    }'
}

# --- k6 output parser ---
#
# k6's text summary contains lines like:
#   http_reqs..........: 12345    823.45/s
#   http_req_duration..: avg=1.21ms ... p(50)=1.0ms p(95)=2.5ms p(99)=4.2ms
# We extract: rps from http_reqs, p50 / p99 from http_req_duration.
# Latency strings keep the unit suffix (ms / us) so consumers can format.

extract_metric_pct() {
    local out="$1" metric="$2" pct="$3"
    # awk -v with single-backslash `\(` collapses to a regex group, not a
    # literal paren. Use a bracket expression `[(]` / `[)]` instead so the
    # parens are unambiguously literal regardless of awk -v escaping rules.
    printf '%s' "$out" | awk -v m="$metric" -v p="^p[(]${pct}[)]=" '$0 ~ "^[[:space:]]*"m {
        for (i = 1; i <= NF; i++) if ($i ~ p) { sub(p, "", $i); print $i; exit }
    }'
}

parse_k6_output() {
    local out="$1"
    local kind="$2"
    local rps_metric duration_metric
    case "$kind" in
        ws)
            # WebSocket bench: count echoed messages received/sec.
            # Latency: the `k6/websockets` (stable) module does not auto-
            # populate the legacy `ws_ping` Trend that `k6/ws` did, so
            # all three WS scripts (`ws-echo.js` / `ws-large.js` /
            # `ws-slow-consumer.js`) emit a JS-side `ws_msg_rtt_ms` Trend
            # via `Date.now()` deltas. ms granularity is fine for >1 ms
            # RTTs; for sub-millisecond control-frame RTT use `ws.ping()`
            # in the script and read the engine-specific WS frame stats
            # directly.
            rps_metric="ws_msgs_received"
            duration_metric="ws_msg_rtt_ms"
            ;;
        *)
            rps_metric="http_reqs"
            duration_metric="http_req_duration"
            ;;
    esac
    local rps p50 p99
    rps=$(printf '%s' "$out" | awk -v m="$rps_metric" '$0 ~ "^[[:space:]]*"m {
        for (i = NF; i > 0; i--) if ($i ~ /\/s$/) { sub(/\/s$/, "", $i); print $i; exit }
    }')
    p50=$(extract_metric_pct "$out" "$duration_metric" "50")
    p99=$(extract_metric_pct "$out" "$duration_metric" "99")
    printf '%s|%s|%s\n' "$rps" "$p50" "$p99"
}

# Extract the success rate from k6's checks output. k6 emits two formats
# depending on version:
#   v1.x:  `checks_succeeded...: 99.97% 1234567 out of 1234999`
#          (paired with a separate `checks_failed` line)
#   v0.x:  `checks.....................: 99.97%   1234567 out of 1234999`
#          (single combined line)
# Both expose the success percentage as the first %-token on the line, so
# we match `checks` or `checks_succeeded` and grab the first %-suffixed
# field. Used to flag corrupt benchmarks (e.g. SSE bodies that fail body-
# size validation under HTTP keep-alive bugs) so the harness can mark
# them FAILED instead of reporting throughput numbers built on failed
# responses.
extract_success_rate() {
    local out="$1"
    printf '%s' "$out" | awk '/^[[:space:]]*checks(_succeeded)?[[:space:].]*:/ {
        for (i = 1; i <= NF; i++) if ($i ~ /%$/) { sub(/%$/, "", $i); print $i; exit }
    }'
}

# --- Run ---

ALL_RPS=()
BEST_RPS=0
BEST_P50=""
BEST_P99=""

TEMP_START=""
[ "$TEMP_CAPTURE" = 1 ] && TEMP_START=$(read_temp_c)

for run in $(seq 1 "$RUNS"); do
    kill_port "$PORT"
    sleep 1

    # Reset per-iteration state. INVALID is consulted by the post-parse
    # block to short-circuit success-rate validation when a run times
    # out; K6_EXIT carries the wall-clock timeout signal from the
    # `timeout(1)` wrapper.
    INVALID=false
    K6_EXIT=0

    # See bench-one.sh for the rationale: setsid lets us kill the entire
    # process group so JVM helper threads / native forks don't leak.
    USED_SETSID=false
    # Daemonise the server by both detaching the process group (setsid) AND
    # closing every inherited fd above stdio. The fd-close is not optional —
    # bash 5.3+ keeps process substitution FIFOs alive for the entire
    # surrounding compound command (`for ... done`, `while ... done`,
    # `{ ... }`), so `bench-stream-all.sh`'s
    #   `for scenario in ...; do while ... done < <(build_engine_list); done`
    # leaves a live FIFO fd that is inherited by every backgrounded server
    # forked from inside the compound. Empirically (macOS bash 5.3.9) that
    # inherited fd correlates with the kqueue server's READ filter failing
    # to fire on the ws-large 50-VU workload — `bench-stream-all.sh ws-large`
    # showed `ktor-cio-keel-kqueue` + `ktor-keel-kqueue` hanging at
    # `client SIGKILL @ 90 s` until this guard was added, while the same
    # engine via `bench-stream-one.sh` directly (no compound wrapper) passed.
    # Closing fds 3..255 before `exec`ing the server matches the standard
    # daemon-hardening idiom and removes the regression independently of
    # which scenario / engine combination triggers the surfacing pattern.
    _CLOSE_INHERITED_FDS='for fd in $(/usr/bin/seq 3 255); do eval "exec $fd<&-" 2>/dev/null || true; done'
    if command -v setsid >/dev/null 2>&1; then
        setsid bash -c "$_CLOSE_INHERITED_FDS"' ; exec "$@"' bash "$@" >/dev/null 2>&1 &
        USED_SETSID=true
    else
        bash -c "$_CLOSE_INHERITED_FDS"' ; exec "$@"' bash "$@" >/dev/null 2>&1 &
    fi
    PID=$!

    kill_server() {
        if [ "$USED_SETSID" = true ]; then
            kill -TERM -- "-$PID" 2>/dev/null || true
        else
            kill "$PID" 2>/dev/null || true
        fi
    }

    # Validate HTTP status, not just TCP connect (5xx would otherwise pass).
    # Track per-iteration curl exit code distribution so a READY failure
    # can be attributed (exit 7 = TCP refused, exit 28 = server accepted
    # TCP but never responded — server-hang fingerprint, exit 0 +
    # non-2xx/3xx = warmup HTTP error). See bench-one.sh for rationale.
    READY=false
    declare -A CURL_EXIT_COUNTS=()
    for _ in $(seq 1 "$READY_TIMEOUT"); do
        STATUS=$(curl -sk --max-time 2 -o /dev/null -w '%{http_code}' \
            "${SCHEME}://127.0.0.1:${PORT}${READY_ENDPOINT}" 2>/dev/null)
        CURL_EXIT=$?
        CURL_EXIT_COUNTS[$CURL_EXIT]=$(( ${CURL_EXIT_COUNTS[$CURL_EXIT]:-0} + 1 ))
        case "$STATUS" in
            2??|3??) READY=true; break ;;
        esac
        sleep 0.5
    done

    if [ "$READY" = false ]; then
        # Pick dominant exit as attribution; tie-break favours non-zero.
        BEST_EXIT=0
        BEST_COUNT=-1
        for e in "${!CURL_EXIT_COUNTS[@]}"; do
            c=${CURL_EXIT_COUNTS[$e]}
            if [ "$c" -gt "$BEST_COUNT" ] || { [ "$c" -eq "$BEST_COUNT" ] && [ "$e" != 0 ]; }; then
                BEST_EXIT=$e
                BEST_COUNT=$c
            fi
        done
        echo "$NAME|FAILED|-|-|[READY_TIMEOUT_${BEST_EXIT}]|READY_TIMEOUT_${BEST_EXIT}" >&2
        echo "$NAME|FAILED|-|-"
        kill_port "$PORT"
        kill_server
        wait "$PID" 2>/dev/null || true
        exit 1
    fi

    # SAFE_NAME is computed once near the top of the run setup (alongside
    # JFR / GC log filename derivation); reuse it here for raw output.
    RAW_FILE="${RESULTS_DIR}/${SAFE_NAME}-${SCENARIO}-${K6_VUS}vu-${K6_DURATION}-${TIMESTAMP}-run${run}.txt"

    if [ "$PARSER" = "wsbench" ]; then
        # Custom Go client. Already emits the canonical
        # `<name>|<rps>|<p50>|<p99>` row, so no parsing needed —
        # capture stdout straight as the bench output.
        #
        # The wsbench scenario name is derived from the bench-stream-one
        # scenario: ws-fragment → fragment-recv, ws-deflate → deflate.
        # The deflate scenario picks its own default path (/ws-deflate)
        # so no -path is passed; -compression toggles the A/B leg.
        WSBENCH_ARGS=(
            -name="$NAME"
            -scheme="$WS_SCHEME"
            -host=127.0.0.1
            -port="$PORT"
            -vus="$K6_VUS"
            -duration="$K6_DURATION"
        )
        case "$SCENARIO" in
            ws-deflate)
                WSBENCH_ARGS+=(
                    -scenario=deflate
                    -bytes="${BENCH_WS_FRAG_BYTES:-4096}"
                    -compression="${BENCH_WS_COMPRESSION:-true}"
                )
                ;;
            *)
                WSBENCH_ARGS+=(
                    -scenario=fragment-recv
                    -bytes="${BENCH_WS_FRAG_BYTES:-4096}"
                    -fragments="${BENCH_WS_FRAG_COUNT:-4}"
                )
                ;;
        esac
        K6_OUT=$(
            run_k6_with_timeout "$SCRIPT" "${WSBENCH_ARGS[@]}" 2>&1
        )
        K6_EXIT=$?
        # 124 = SIGTERM-induced timeout exit (child responded to TERM).
        # 137 = SIGKILL (128+9), used when the child ignored SIGTERM and
        # `timeout -k` had to escalate. Both indicate wall-clock exhaustion.
        if [ "$K6_EXIT" = "124" ] || [ "$K6_EXIT" = "137" ]; then
            K6_OUT="[TIMEOUT exit=${K6_EXIT} after ${K6_TIMEOUT} (+${K6_KILL_AFTER} kill grace)]
$K6_OUT"
        fi
        printf '%s\n' "$K6_OUT" > "$RAW_FILE"
        # The wsbench output line is already in the right shape; pull
        # only the line starting with the engine name. Use NAME (not
        # SAFE_NAME) because wsbench is invoked with -name="$NAME" above
        # and outputs the name verbatim; SAFE_NAME replaces ':' with '-'
        # which would never match.
        ROW=$(printf '%s' "$K6_OUT" | grep -E "^${NAME}\|" | tail -1)
        if [ -z "$ROW" ]; then
            PARSED="||"
        else
            PARSED=$(printf '%s' "$ROW" | cut -d'|' -f2-)
        fi
    else
        # k6 with p50/p99 enabled in the summary trend stats.
        K6_OUT=$(
            HOST=127.0.0.1 PORT="$PORT" \
            SCHEME="$SCHEME" WS_SCHEME="$WS_SCHEME" \
            VUS="$K6_VUS" DURATION="$K6_DURATION" \
            PAYLOAD_KB="${BENCH_PAYLOAD_KB:-64}" \
            UPLOAD_BYTES="${BENCH_UPLOAD_BYTES:-0}" \
            UPLOAD_PATH="${BENCH_UPLOAD_PATH:-/upload-stream}" \
            COUNT="${BENCH_SSE_COUNT:-100}" SIZE="${BENCH_SSE_SIZE:-1024}" \
            PARTS="${BENCH_MULTIPART_PARTS:-5}" PART_BYTES="${BENCH_MULTIPART_PART_BYTES:-4096}" \
            METHODS="${BENCH_METHODS:-GET,POST,PUT,DELETE,PATCH,OPTIONS}" \
            ID_RANGE="${BENCH_PATH_ID_RANGE:-100}" \
            SLOW_INTERVAL_MS="${BENCH_SLOW_INTERVAL_MS:-100}" \
            BURST_PINGS="${BENCH_WS_BURST_PINGS:-16}" \
            CONSUME_DELAY_MS="${BENCH_WS_CONSUME_DELAY_MS:-50}" \
            PAYLOAD_BYTES="${BENCH_WS_PAYLOAD:-256}" \
            PAYLOAD_TYPE="${BENCH_WS_TYPE:-text}" \
            CLOSE_HANDSHAKE="${BENCH_WS_CLOSE_HANDSHAKE:-false}" \
            WS_LARGE_BYTES="${BENCH_WS_LARGE_BYTES:-1048576}" \
            WS_PATH="${BENCH_WS_PATH:-/ws-held/64/chunks}" \
            WS_WINDOW="${BENCH_WS_WINDOW:-}" \
            PING_PONGS="${BENCH_WS_PING_PONGS:-0}" \
            CONNECTION_CLOSE="${BENCH_HTTP_CONNECTION_CLOSE:-false}" \
            COMPRESSION_TYPE="${BENCH_COMPRESSION_TYPE:-gzip}" \
            COMPRESSION_STRICT="${BENCH_COMPRESSION_STRICT:-true}" \
            COMPRESSION_UPLOAD_STRICT="${BENCH_COMPRESSION_UPLOAD_STRICT:-true}" \
            run_k6_with_timeout k6 run --quiet --no-color \
                --summary-trend-stats="avg,min,med,max,p(50),p(95),p(99)" \
                "$SCRIPT" 2>&1
        )
        K6_EXIT=$?
        # See above for 124 vs 137 distinction.
        if [ "$K6_EXIT" = "124" ] || [ "$K6_EXIT" = "137" ]; then
            K6_OUT="[TIMEOUT exit=${K6_EXIT} after ${K6_TIMEOUT} (+${K6_KILL_AFTER} kill grace)]
$K6_OUT"
        fi
        printf '%s\n' "$K6_OUT" > "$RAW_FILE"
        PARSED=$(parse_k6_output "$K6_OUT" "$PARSER")
    fi
    RPS=$(echo "$PARSED" | cut -d'|' -f1)
    P50=$(echo "$PARSED" | cut -d'|' -f2)
    P99=$(echo "$PARSED" | cut -d'|' -f3)

    # Shut down the server now (was after the parse block) so we can
    # decode its exit signal before classifying the run. See bench-one.sh
    # for the full rationale.
    kill_port "$PORT"
    kill_server
    wait "$PID" 2>/dev/null
    SERVER_EXIT_STATUS=$?

    SERVER_DIED_BY=""
    if [ "$SERVER_EXIT_STATUS" -gt 128 ]; then
        case $((SERVER_EXIT_STATUS - 128)) in
            15) SERVER_DIED_BY="SIGTERM" ;;
            9)  SERVER_DIED_BY="SIGKILL" ;;
            11) SERVER_DIED_BY="SIGSEGV" ;;
            6)  SERVER_DIED_BY="SIGABRT" ;;
            10) SERVER_DIED_BY="SIGBUS" ;;
            7)  SERVER_DIED_BY="SIGBUS" ;;
            8)  SERVER_DIED_BY="SIGFPE" ;;
            4)  SERVER_DIED_BY="SIGILL" ;;
            *)  SERVER_DIED_BY="SIG$((SERVER_EXIT_STATUS - 128))" ;;
        esac
    fi

    SERVER_CRASHED=false
    case "$SERVER_DIED_BY" in
        SIGSEGV|SIGABRT|SIGBUS|SIGFPE|SIGILL) SERVER_CRASHED=true ;;
    esac

    # Surface k6 wall-clock timeout as a distinct outcome. Without
    # this the parsed RPS / p50 / p99 are all empty and the row looks like
    # a silent "no data" line — operators couldn't tell hang from missing
    # measurement.
    if [ "$K6_EXIT" = "124" ] || [ "$K6_EXIT" = "137" ]; then
        INVALID=true
        RPS=""
        if [ "$K6_EXIT" = "137" ]; then
            # SIGKILL escalation — child ignored SIGTERM; surface this as
            # KILLED in the row so the operator sees that the friendly
            # shutdown path was bypassed.
            P50="KILLED ${K6_TIMEOUT}"
        else
            P50="TIMEOUT ${K6_TIMEOUT}"
        fi
        P99="-"
    fi

    # Server died by a fatal signal mid-run — override RPS / P50 /
    # P99 to surface the crash even if k6 emitted numeric values for the
    # part of the run before the crash.
    if [ "$SERVER_CRASHED" = true ] && [ "$INVALID" != true ]; then
        INVALID=true
        RPS=""
        P50="CRASH ${SERVER_DIED_BY}"
        P99="-"
    fi

    # Validate success rate. k6's `http_reqs` / `ws_msgs_received` count
    # everything including failed responses, so a server that returns
    # 99% errors at 50K/s would otherwise look like "50K RPS". If checks
    # are present and below the threshold, treat the run as failed and
    # surface the failure ratio in the latency columns so it isn't
    # silently dropped from the summary table.
    SUCCESS_RATE=$(extract_success_rate "$K6_OUT")
    THRESHOLD="${BENCH_K6_SUCCESS_THRESHOLD:-95}"
    INVALID="${INVALID:-false}"
    if [ "$INVALID" != true ] && [ -n "$SUCCESS_RATE" ] && awk "BEGIN {exit !($SUCCESS_RATE < $THRESHOLD)}" 2>/dev/null; then
        INVALID=true
        RPS=""
        P50="checks=${SUCCESS_RATE}%"
        P99="-"
    fi

    ALL_RPS+=("$RPS")

    if [ "$INVALID" = true ]; then
        # Failure marker wins over any prior run's RPS so the operator
        # sees the corruption at a glance.
        BEST_P50="$P50"
        BEST_P99="$P99"
    elif [ -n "$RPS" ] && awk "BEGIN {exit !($RPS > $BEST_RPS)}" 2>/dev/null; then
        BEST_RPS="$RPS"
        BEST_P50="$P50"
        BEST_P99="$P99"
    fi

    if [ "$run" -lt "$RUNS" ]; then
        sleep "$COOLDOWN"
    fi
done

TEMP_END=""
[ "$TEMP_CAPTURE" = 1 ] && TEMP_END=$(read_temp_c)
TEMP_FIELD=$(format_temp_delta "$TEMP_START" "$TEMP_END")

if [ "$RUNS" -gt 1 ]; then
    MEDIAN_RPS=$(median "${ALL_RPS[@]}")
    echo "$NAME|$MEDIAN_RPS|$BEST_P50|$BEST_P99|[${ALL_RPS[*]}]${TEMP_FIELD:+|temp=$TEMP_FIELD}"
else
    echo "$NAME|${ALL_RPS[0]}|$BEST_P50|$BEST_P99${TEMP_FIELD:+|temp=$TEMP_FIELD}"
fi

# Surface JFR / GC log paths so the operator knows where the artefacts
# went (stderr keeps the bench's stdout pipe clean for downstream
# parsing into summary tables).
if [ -n "$JFR_FILE" ]; then
    if [ -f "$JFR_FILE" ]; then
        printf 'jfr: %s\n' "$JFR_FILE" >&2
    else
        printf 'warning: JFR file not produced (expected %s) — check JVM version supports `-XX:StartFlightRecording`\n' "$JFR_FILE" >&2
    fi
fi
if [ -n "$GC_LOG_FILE" ]; then
    if [ -f "$GC_LOG_FILE" ]; then
        printf 'gc-log: %s\n' "$GC_LOG_FILE" >&2
    else
        printf 'warning: GC log not produced (expected %s)\n' "$GC_LOG_FILE" >&2
    fi
fi
