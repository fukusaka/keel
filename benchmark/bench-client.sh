#!/usr/bin/env bash
# bench-client.sh — HTTP *client* benchmark driver (keel-client as the SUT).
#
# The inverse of bench-one.sh / bench-remote.sh: instead of an external load
# generator (wrk / k6) driving a keel *server*, here the process under test is
# an HTTP *client* driving a fixture server, and we measure the client's
# throughput / latency / per-request allocation. Follows common
# HTTP-client-benchmark practice (Ktor client-benchmarks, undici, OkHttp
# MockWebServer, h2load, wrk2): reference-client A/B, HdrHistogram latency, and
# bytes/op as a first-class metric.
#
# Fixture = a SEPARATE process the client connects to over loopback, NOT an
# in-process server: sharing the client's JVM (heap / GC / CPU / thread
# scheduler) would contaminate the numbers — and the fixture's own allocations
# would pollute the client's per-request bytes/op. The default fixture is
# rust-bench (axum / hyper / tokio: separate OS process, no JVM, no GC/JIT,
# high headroom, and the same /hello /large routes as the server bench), a
# neutral server relative to the JVM clients under test. This script manages
# the fixture's lifecycle (build -> start -> readiness -> run -> stop).
#
# This runs the LOOPBACK tier. The MACRO tier (real-NIC two-host, fixture on a
# separate host, tc netem RTT, server-headroom + client-non-saturation checks)
# reuses the same harness with BENCH_CLIENT_TARGET pointing at a remote fixture;
# it lands in a later increment.
#
# Usage:
#   ./benchmark/bench-client.sh
#
# Env vars:
#   BENCH_CLIENT_TYPES    space list of client drivers (default "java ktor-cio";
#                         "keel" pending the standalone keel-client-http, 12b)
#   BENCH_CLIENT_ENDPOINT fixture path (default /hello; /large for throughput)
#   BENCH_CLIENT_CONNS    concurrent connections / pool size (default 50)
#   BENCH_CLIENT_WARMUP   warm-up seconds, discarded (default 3)
#   BENCH_CLIENT_DURATION measurement seconds (default 10)
#   BENCH_RUNS            runs per client, median reported (default 3)
#   BENCH_FIXTURE         fixture kind: "rust-bench" (default). For an
#                         externally-managed fixture set BENCH_CLIENT_TARGET.
#   BENCH_FIXTURE_PORT    loopback port for the managed fixture (default 18080)
#   BENCH_CLIENT_TARGET   external fixture base URL (e.g. a remote host for the
#                         macro tier); when set, no fixture is started here
#
# Methodology guardrails (coordinated omission, SUT isolation):
#   - Latency here is CLOSED-loop (coordinated-omission susceptible); the
#     open-loop constant-rate mode (CO-corrected p99.9) is a later increment.
#   - SUT isolation is two-sided: keep the fixture (rust-bench) unsaturated AND
#     the client host below ~85% CPU, or the number is a fake plateau.
#   - Record BENCH_RUNS>=3 median, not a single run, for any docs cell.
set -euo pipefail

cd "$(dirname "$0")/.."

TYPES="${BENCH_CLIENT_TYPES:-java ktor-cio}"
ENDPOINT="${BENCH_CLIENT_ENDPOINT:-/hello}"
CONNS="${BENCH_CLIENT_CONNS:-50}"
WARMUP="${BENCH_CLIENT_WARMUP:-3}"
DURATION="${BENCH_CLIENT_DURATION:-10}"
RUNS="${BENCH_RUNS:-3}"
FIXTURE="${BENCH_FIXTURE:-rust-bench}"
FIXTURE_PORT="${BENCH_FIXTURE_PORT:-18080}"
TARGET="${BENCH_CLIENT_TARGET:-}"

FIXTURE_PID=""
cleanup() { [ -n "$FIXTURE_PID" ] && kill "$FIXTURE_PID" 2>/dev/null || true; }
trap cleanup EXIT INT TERM

start_rust_bench() {
  local bin="benchmark/rust-bench/target/release/rust-bench"
  if [ ! -x "$bin" ]; then
    command -v cargo >/dev/null 2>&1 || {
      echo "cargo not found: install Rust to build rust-bench, or point BENCH_CLIENT_TARGET at an external fixture" >&2
      exit 1
    }
    echo "building rust-bench (cargo build --release)..." >&2
    ( cd benchmark/rust-bench && cargo build --release >/dev/null 2>&1 )
  fi
  echo "starting rust-bench fixture on 127.0.0.1:${FIXTURE_PORT}..." >&2
  "$bin" --port="$FIXTURE_PORT" >/dev/null 2>&1 &
  FIXTURE_PID=$!
  TARGET="http://127.0.0.1:${FIXTURE_PORT}"
}

wait_ready() {
  local url="${1}/hello"
  for _ in $(seq 1 100); do
    if curl -sf -o /dev/null "$url" 2>/dev/null; then return 0; fi
    if [ -n "$FIXTURE_PID" ] && ! kill -0 "$FIXTURE_PID" 2>/dev/null; then
      echo "fixture process exited before becoming ready" >&2
      exit 1
    fi
    sleep 0.2
  done
  echo "fixture at $url not ready after 20s" >&2
  exit 1
}

if [ -n "$TARGET" ]; then
  echo "# external fixture: $TARGET (lifecycle not managed here)" >&2
elif [ "$FIXTURE" = "rust-bench" ]; then
  start_rust_bench
else
  echo "unknown BENCH_FIXTURE='$FIXTURE' (expected 'rust-bench', or set BENCH_CLIENT_TARGET)" >&2
  exit 1
fi
wait_ready "$TARGET"

echo "resolving JVM classpath (writeClasspath is config-cache-incompatible)..." >&2
./gradlew --no-configuration-cache -Pbenchmark :benchmark:jvmMainClasses :benchmark:writeClasspath >/dev/null 2>&1
CP="$(./benchmark/bench-jvm-cp.sh resolve)"

echo "# client bench: endpoint=$ENDPOINT conns=$CONNS warmup=${WARMUP}s duration=${DURATION}s runs=$RUNS fixture=$TARGET"
echo "# format: <client><endpoint>|<req/s>|<p50ms>|<p99ms>|<p99.9ms>|<maxms>|<bytes/op>|<errors>"

median() { sort -t'|' -k2 -n | awk -F'|' '{a[NR]=$0} END{print a[int((NR+1)/2)]}'; }

for type in $TYPES; do
  tmp="$(mktemp)"
  for run in $(seq 1 "$RUNS"); do
    java -cp "$CP" io.github.fukusaka.keel.benchmark.JvmMainKt \
      --role=client --client-type="$type" --client-target="$TARGET" \
      --client-endpoint="$ENDPOINT" --client-connections="$CONNS" \
      --client-warmup="$WARMUP" --client-duration="$DURATION" 2>/dev/null \
      | grep '|' >> "$tmp" || echo "  run $run for $type failed" >&2
    sleep 2
  done
  [ -s "$tmp" ] && median < "$tmp" || echo "$type: no successful runs" >&2
  rm -f "$tmp"
done
