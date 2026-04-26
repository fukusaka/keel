#!/usr/bin/env bash
# bench-remote-slow.sh — slow-path benchmark scenarios for keel POSIX engines.
#
# Wraps bench-remote.sh with two server-side modifications that together
# force partial-write firing in flushGather/flushSingle on a real-network
# bench:
#   1. tc qdisc on the server interface — adds latency / jitter / loss /
#      rate cap so the wrk client's ack stream can no longer drain the
#      server's send buffer at line rate.
#   2. small SO_SNDBUF (default 4 KiB) on every accepted child socket —
#      via --send-buffer=N, which keel POSIX engines wire through
#      `BindConfig.childSocketOptions` to `setsockopt(SO_SNDBUF)`.
#
# With (1) + (2), even a small payload like /hello (13 B) saturates the
# kernel send buffer almost immediately, and gather writes on
# back-to-back keep-alive responses observe partial writes
# (`writev` returns less than total). The
# `AbstractIoTransport.partialWriteCount` counter then logs a non-zero
# `ratio_bp` on transport teardown — proof that the bench is actually
# exercising the path that the deferred slow-path optimisations
# (pendingWrites ArrayDeque / flushGather mutableListOf) target.
#
# Usage: ./benchmark/bench-remote-slow.sh <name> <command> [args...]
#
# Required environment variables (in addition to bench-remote.sh):
#   BENCH_REMOTE_HOST    Server host (ssh target that runs <command>).
#   BENCH_CLIENT_HOST    wrk client host (ssh target that drives the load).
#   BENCH_TC_INTERFACE   Server interface to apply tc qdisc on (e.g. eth0).
#                        Passwordless sudo on the server host is required.
#
# Optional environment variables:
#   BENCH_SO_SNDBUF      Per-accepted-socket SO_SNDBUF in bytes.
#                        Default: 4096. Forwarded as --send-buffer=N to
#                        the benchmark binary.
#   BENCH_NETEM_DELAY    netem delay parameter ("delay <X> <jitter>").
#                        Default: "20ms 5ms". Empty string disables netem
#                        delay (use rate-only or loss-only scenarios).
#   BENCH_NETEM_LOSS     netem loss parameter ("loss <X>%").
#                        Default: "" (no loss).
#   BENCH_NETEM_RATE     tbf rate cap (e.g. "100mbit"). Layered as a child
#                        qdisc when set. Default: "" (no rate cap).
#   BENCH_TC_HANDLE      Major qdisc handle. Default: "1:".
#
# Plus all bench-remote.sh tuning knobs (BENCH_RUNS, BENCH_SHUFFLE,
# BENCH_WRK_THREADS, BENCH_WRK_CONNS, BENCH_ENDPOINT, etc).
#
# Output format: identical to bench-remote.sh, prefixed with the
# transport-stats summary line scraped from the server log.
#
# Cleanup: tc qdisc on the server is removed via `trap EXIT` regardless
# of bench-remote.sh exit status. If this script is killed by SIGKILL
# (uncatchable), run manually:
#   ssh "$BENCH_REMOTE_HOST" "sudo tc qdisc del dev <iface> root"
#
# Example:
#   BENCH_REMOTE_HOST=bench.example BENCH_CLIENT_HOST=client.example \
#     BENCH_TC_INTERFACE=eth0 \
#     BENCH_SO_SNDBUF=4096 BENCH_NETEM_DELAY="50ms 10ms" BENCH_NETEM_RATE=10mbit \
#     BENCH_RUNS=3 \
#     ./benchmark/bench-remote-slow.sh pipeline-http-epoll-slow \
#       benchmark/build/bin/linuxX64/releaseExecutable/benchmark.kexe \
#       --engine=pipeline-http-epoll --port=18090

set -uo pipefail

NAME="${1:?Usage: bench-remote-slow.sh <name> <command> [args...]}"
shift
if [ "$#" -lt 1 ]; then
    echo "Usage: bench-remote-slow.sh <name> <command> [args...]" >&2
    exit 1
fi

: "${BENCH_REMOTE_HOST:?BENCH_REMOTE_HOST is required (server host for ssh)}"
: "${BENCH_CLIENT_HOST:?BENCH_CLIENT_HOST is required (wrk client host for ssh)}"
: "${BENCH_TC_INTERFACE:?BENCH_TC_INTERFACE is required (server iface for tc qdisc)}"

REMOTE_HOST="$BENCH_REMOTE_HOST"
TC_IFACE="$BENCH_TC_INTERFACE"
SO_SNDBUF="${BENCH_SO_SNDBUF:-4096}"
NETEM_DELAY="${BENCH_NETEM_DELAY-20ms 5ms}"
NETEM_LOSS="${BENCH_NETEM_LOSS:-}"
NETEM_RATE="${BENCH_NETEM_RATE:-}"
TC_HANDLE="${BENCH_TC_HANDLE:-1:}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# --- tc qdisc lifecycle on the server ---

apply_tc() {
    local netem_args=""
    if [ -n "$NETEM_DELAY" ]; then netem_args="$netem_args delay $NETEM_DELAY"; fi
    if [ -n "$NETEM_LOSS" ];  then netem_args="$netem_args loss $NETEM_LOSS"; fi

    if [ -z "$netem_args" ] && [ -z "$NETEM_RATE" ]; then
        echo "ERROR: at least one of BENCH_NETEM_DELAY / BENCH_NETEM_LOSS / BENCH_NETEM_RATE must be set" >&2
        exit 1
    fi

    # Brace group keeps the ssh-side error-or-success printable in one go.
    # `sudo -n` so the script fails fast if passwordless sudo is not
    # configured for `tc` on the server.
    if [ -n "$netem_args" ]; then
        ssh -n "$REMOTE_HOST" "sudo -n tc qdisc add dev ${TC_IFACE} root handle ${TC_HANDLE} netem ${netem_args}" \
            || { echo "ERROR: tc qdisc add netem failed" >&2; exit 1; }
        if [ -n "$NETEM_RATE" ]; then
            # Layer tbf as a child of netem so traffic is shaped through
            # both: netem at parent, tbf at handle 10:.
            ssh -n "$REMOTE_HOST" "sudo -n tc qdisc add dev ${TC_IFACE} parent ${TC_HANDLE} handle 10: tbf rate ${NETEM_RATE} burst 32kbit latency 400ms" \
                || { echo "ERROR: tc qdisc add tbf failed" >&2; cleanup_tc; exit 1; }
        fi
    else
        # rate-only: tbf at root.
        ssh -n "$REMOTE_HOST" "sudo -n tc qdisc add dev ${TC_IFACE} root handle ${TC_HANDLE} tbf rate ${NETEM_RATE} burst 32kbit latency 400ms" \
            || { echo "ERROR: tc qdisc add tbf failed" >&2; exit 1; }
    fi

    echo "[bench-remote-slow] tc qdisc applied on ${REMOTE_HOST}:${TC_IFACE}" >&2
    [ -n "$netem_args" ] && echo "[bench-remote-slow]   netem:${netem_args}" >&2
    [ -n "$NETEM_RATE" ] && echo "[bench-remote-slow]   tbf rate ${NETEM_RATE}" >&2
}

cleanup_tc() {
    # Idempotent — `del root` removes whatever is at root, no-op if nothing.
    # `|| true` swallows the noise when there is no qdisc to remove
    # (already cleaned up, or apply_tc never ran).
    ssh -n "$REMOTE_HOST" "sudo -n tc qdisc del dev ${TC_IFACE} root 2>/dev/null || true" >/dev/null 2>&1 || true
    echo "[bench-remote-slow] tc qdisc cleanup done on ${REMOTE_HOST}:${TC_IFACE}" >&2
}

trap cleanup_tc EXIT INT TERM

# --- Run bench-remote.sh with --send-buffer appended ---

apply_tc

# Build the wrapped command: original args + --send-buffer=N (overrides any
# pre-existing --send-buffer in the caller's args because later wins on
# BenchmarkConfig.parse — last `--key=value` token observed).
SLOW_ARGS=("$@" "--send-buffer=${SO_SNDBUF}")

echo "[bench-remote-slow] forwarding to bench-remote.sh with --send-buffer=${SO_SNDBUF}" >&2
"${SCRIPT_DIR}/bench-remote.sh" "$NAME" "${SLOW_ARGS[@]}"
EXIT_CODE=$?

# --- Optional: scrape transport stats from the server log ---
#
# bench-remote.sh writes server stdout to /tmp/bench-remote-${NAME}.log
# on the server. AbstractIoTransport.logTransportStatsOnClose emits one
# line per closed transport at debug level. If KEEL_LOG_LEVEL=debug is
# enabled in the benchmark binary, scrape an aggregated view here.
LOG_PATH="/tmp/bench-remote-${NAME}.log"
TRANSPORT_LINES=$(ssh -n "$REMOTE_HOST" "grep -c 'transport stats:' ${LOG_PATH} 2>/dev/null || echo 0")
if [ "$TRANSPORT_LINES" -gt 0 ]; then
    PARTIAL_TOTAL=$(ssh -n "$REMOTE_HOST" "grep 'transport stats:' ${LOG_PATH} | awk -F'partial=' '{print \$2}' | awk '{print \$1}' | paste -sd+ - | bc")
    FLUSH_TOTAL=$(ssh -n "$REMOTE_HOST" "grep 'transport stats:' ${LOG_PATH} | awk -F'flush=' '{print \$2}' | awk '{print \$1}' | paste -sd+ - | bc")
    if [ -n "$FLUSH_TOTAL" ] && [ "$FLUSH_TOTAL" != "0" ]; then
        RATIO_PCT=$(awk "BEGIN { printf \"%.2f\", $PARTIAL_TOTAL * 100.0 / $FLUSH_TOTAL }")
        echo "[bench-remote-slow] transport stats: ${TRANSPORT_LINES} closed transports, flush=${FLUSH_TOTAL} partial=${PARTIAL_TOTAL} ratio=${RATIO_PCT}%" >&2
    else
        echo "[bench-remote-slow] transport stats: ${TRANSPORT_LINES} closed transports, no flush activity (counter not reached)" >&2
    fi
else
    echo "[bench-remote-slow] no transport stats lines in ${LOG_PATH} — debug logging may be disabled (try KEEL_LOG_LEVEL=debug)" >&2
fi

exit $EXIT_CODE
