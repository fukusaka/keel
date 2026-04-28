#!/usr/bin/env bash
# bench-keepalive-compare.sh — A/B compare HTTP keep-alive vs Connection: close.
#
# Wraps bench-stream-one.sh: runs the given engine + scenario twice, once
# with HTTP/1.1 keep-alive (the default) and once forcing
# `Connection: close` on every request. Surfaces the per-engine impact of
# socket reuse vs per-request TCP setup + teardown.
#
# Why this exists:
# - keel's engines (and most non-keel JVM engines) optimise the
#   keep-alive case heavily. The Connection: close case adds a TCP
#   handshake + TIME_WAIT churn per request, so the throughput delta
#   exposes how much of the headline RPS is connection reuse vs
#   per-request handler work.
# - Several past bench rows (#394 era) implicitly assumed keep-alive
#   defaults; this wrapper makes the assumption testable side-by-side.
#
# Only HTTP scenarios (`upload`, `sse`) make sense — WebSocket scenarios
# always own their connection lifecycle, so close-per-request is a no-op.
# The wrapper rejects WS scenarios with a clear error.
#
# Usage:
#   ./benchmark/bench-keepalive-compare.sh <name> <scenario> <command> [args...]
#
# All env vars accepted by bench-stream-one.sh are honored. The wrapper
# overrides BENCH_HTTP_CONNECTION_CLOSE per pass.
#
# Output format:
#   <name>-keepalive|<rps>|<p50>|<p99>
#   <name>-close|<rps>|<p50>|<p99>
#   <name>-delta|<close-rps - keepalive-rps as % of keepalive>|—|—
#
# Example:
#   ./benchmark/bench-keepalive-compare.sh ktor-cio upload \
#       java -cp ... io.github.fukusaka.keel.benchmark.JvmMainKt --engine=ktor-cio --port=18090

set -uo pipefail

NAME="${1:?Usage: bench-keepalive-compare.sh <name> <scenario> <command> [args...]}"
SCENARIO="${2:?Usage: bench-keepalive-compare.sh <name> <scenario> <command> [args...]}"
shift 2

case "$SCENARIO" in
    upload|sse|multipart|slow-upload) ;;
    ws-echo|ws-large|ws-fragment|ws-slow-consumer)
        echo "error: $SCENARIO owns its connection lifecycle; keep-alive vs close A/B is meaningless." >&2
        echo "       Use bench-stream-one.sh directly for WS scenarios." >&2
        exit 2
        ;;
    *)
        echo "error: unsupported scenario '$SCENARIO'." >&2
        echo "       Supported: upload, sse, multipart, slow-upload." >&2
        exit 2
        ;;
esac

SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
RUNNER="$SCRIPT_DIR/bench-stream-one.sh"

# Tiny helper: extract the RPS field from a `<name>|<rps>|<p50>|<p99>` row.
extract_rps() {
    printf '%s' "$1" | awk -F'|' '{print $2}'
}

# Pass 1: keep-alive (default).
PASS1=$(BENCH_HTTP_CONNECTION_CLOSE=false "$RUNNER" "$NAME" "$SCENARIO" "$@" 2>&1 | tail -1)
PASS1_LABELED="${NAME}-keepalive|${PASS1#*|}"
printf '%s\n' "$PASS1_LABELED"

# Pass 2: Connection: close. Sleep briefly so TIME_WAIT sockets from
# pass 1 do not eat the listener backlog when pass 2's k6 reconnects.
sleep 3
PASS2=$(BENCH_HTTP_CONNECTION_CLOSE=true "$RUNNER" "$NAME" "$SCENARIO" "$@" 2>&1 | tail -1)
PASS2_LABELED="${NAME}-close|${PASS2#*|}"
printf '%s\n' "$PASS2_LABELED"

# Compute Δ% (close throughput as % of keep-alive throughput; negative
# numbers mean close is slower, which is the typical case).
RPS1=$(extract_rps "$PASS1_LABELED")
RPS2=$(extract_rps "$PASS2_LABELED")
if [ -n "$RPS1" ] && [ -n "$RPS2" ] && \
   awk "BEGIN {exit !($RPS1 > 0 && $RPS2 > 0)}" 2>/dev/null; then
    DELTA_PCT=$(awk -v a="$RPS1" -v b="$RPS2" \
        'BEGIN {printf "%+.1f%%", (b - a) / a * 100}')
    printf '%s-delta|%s|—|—\n' "$NAME" "$DELTA_PCT"
else
    # Either pass failed or k6 reported a non-numeric RPS. Surface it.
    printf '%s-delta|N/A|—|—\n' "$NAME"
fi
