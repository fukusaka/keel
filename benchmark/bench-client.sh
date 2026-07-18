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
#   BENCH_CLIENT_TYPES    space list of client drivers (default "java ktor-cio").
#                         JVM refs: java (direct java.net.http), okhttp, apache5
#                         (direct libraries), ktor-java / ktor-okhttp /
#                         ktor-apache5 (delegating Ktor engines — all reuse
#                         keep-alive), ktor-cio (churns, KTOR-6503). Native refs
#                         (separate binaries, auto-built): rust-reqwest,
#                         rust-hyper, go-nethttp, go-fasthttp, libcurl,
#                         swift-nsurlsession (macOS). "keel" pending the
#                         standalone keel-client-http (Phase 12b).
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
#   - Default latency is CLOSED-loop (coordinated-omission susceptible), which is
#     fine for throughput but under-reports the tail. For trustworthy p99.9 set
#     BENCH_CLIENT_MODE=open + BENCH_CLIENT_RATE=<req/s below max>: constant-rate,
#     latency measured from the intended send time (CO-corrected, JVM clients).
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
# roundrobin (load-balanced fan-out) | pinned (worker i -> host i). Only matters
# when BENCH_CLIENT_TARGET is a comma-separated multi-host list.
TARGET_MODE="${BENCH_CLIENT_TARGET_MODE:-roundrobin}"
# Load model: "closed" (max-throughput loop) or "open" (constant BENCH_CLIENT_RATE
# req/s, coordinated-omission-corrected latency). Open-loop is JVM-only for now;
# native reference binaries run closed-loop regardless.
CLIENT_MODE="${BENCH_CLIENT_MODE:-closed}"
CLIENT_RATE="${BENCH_CLIENT_RATE:-0}"

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

# Native reference clients are separate binaries (Rust reqwest / Go net/http /
# C libcurl) that accept the SAME CLI flags as the JVM harness and print the
# SAME result line, so they share the drain + median + fixture logic below.
# Returns the binary path for a native type, or empty for a JVM driver type.
native_bin() {
  case "$1" in
    rust-reqwest) echo "benchmark/rust-bench/target/release/client" ;;
    rust-hyper) echo "benchmark/rust-bench/target/release/client-hyper" ;;
    go-nethttp) echo "benchmark/go-bench/client-bench" ;;
    go-fasthttp) echo "benchmark/go-bench/client-fasthttp" ;;
    libcurl) echo "benchmark/curl-bench/client" ;;
    swift-nsurlsession) echo "benchmark/swift-bench/.build/release/swift-client" ;;
    *) echo "" ;;
  esac
}
build_native() {
  case "$1" in
    rust-reqwest) [ -x "$(native_bin "$1")" ] ||
      ( cd benchmark/rust-bench && cargo build --release --bin client >/dev/null 2>&1 ) ;;
    rust-hyper) [ -x "$(native_bin "$1")" ] ||
      ( cd benchmark/rust-bench && cargo build --release --bin client-hyper >/dev/null 2>&1 ) ;;
    go-nethttp) [ -x "$(native_bin "$1")" ] ||
      ( cd benchmark/go-bench && go build -o client-bench ./cmd/client >/dev/null 2>&1 ) ;;
    go-fasthttp) [ -x "$(native_bin "$1")" ] ||
      ( cd benchmark/go-bench && go build -o client-fasthttp ./cmd/client-fasthttp >/dev/null 2>&1 ) ;;
    libcurl) [ -x "$(native_bin "$1")" ] ||
      ( cd benchmark/curl-bench && make >/dev/null 2>&1 ) ;;
    swift-nsurlsession) [ -x "$(native_bin "$1")" ] ||
      ( cd benchmark/swift-bench && swift build -c release --product swift-client >/dev/null 2>&1 ) ;;
  esac
}

# Resolve the JVM classpath only if at least one JVM driver type is requested.
CP=""
NEED_JVM=false
for t in $TYPES; do [ -z "$(native_bin "$t")" ] && NEED_JVM=true; done
if [ "$NEED_JVM" = true ]; then
  echo "resolving JVM classpath (writeClasspath is config-cache-incompatible)..." >&2
  ./gradlew --no-configuration-cache -Pbenchmark :benchmark:jvmMainClasses :benchmark:writeClasspath >/dev/null 2>&1
  CP="$(./benchmark/bench-jvm-cp.sh resolve)"
fi

echo "# client bench: endpoint=$ENDPOINT conns=$CONNS warmup=${WARMUP}s duration=${DURATION}s runs=$RUNS fixture=$TARGET"
echo "# format: <client><endpoint>|<req/s>|<p50ms>|<p99ms>|<p99.9ms>|<maxms>|<bytes/op>|<errors>"

median() { sort -t'|' -k2 -n | awk -F'|' '{a[NR]=$0} END{print a[int((NR+1)/2)]}'; }

# Count sockets in TIME_WAIT (ss on Linux, netstat on macOS/BSD).
count_time_wait() {
  if command -v ss >/dev/null 2>&1; then
    ss -tan state time-wait 2>/dev/null | grep -c ':' || echo 0
  else
    netstat -an -p tcp 2>/dev/null | grep -c TIME_WAIT || echo 0
  fi
}

# Start every run on clean ephemeral ports. A per-request-churning client (Ktor
# CIO, KTOR-6503) leaves ~10-16k TIME_WAIT sockets per run and self-poisons the
# next run with BindException; pooling clients leave almost none so this returns
# immediately. Loopback TIME_WAIT drains in seconds, so this is cheap when clean.
DRAIN_THRESHOLD="${BENCH_DRAIN_THRESHOLD:-1000}"
drain_ports() {
  local tw
  for _ in $(seq 1 40); do # up to ~120s
    tw="$(count_time_wait)"
    [ "${tw:-0}" -lt "$DRAIN_THRESHOLD" ] && return 0
    sleep 3
  done
  echo "  WARN: TIME_WAIT still ${tw} after drain wait (results may be port-starved)" >&2
}

for type in $TYPES; do
  bin="$(native_bin "$type")"
  [ -n "$bin" ] && build_native "$type"
  tmp="$(mktemp)"
  for run in $(seq 1 "$RUNS"); do
    drain_ports # each run starts on clean ports (no cross-run port poisoning)
    if [ -n "$bin" ]; then
      "$bin" \
        --client-target="$TARGET" --client-target-mode="$TARGET_MODE" \
        --client-endpoint="$ENDPOINT" --client-connections="$CONNS" \
        --client-warmup="$WARMUP" --client-duration="$DURATION" 2>/dev/null \
        | grep '|' >> "$tmp" || echo "  run $run for $type failed" >&2
    else
      java -cp "$CP" io.github.fukusaka.keel.benchmark.JvmMainKt \
        --role=client --client-type="$type" --client-target="$TARGET" \
        --client-target-mode="$TARGET_MODE" \
        --client-mode="$CLIENT_MODE" --client-rate="$CLIENT_RATE" \
        --client-endpoint="$ENDPOINT" --client-connections="$CONNS" \
        --client-warmup="$WARMUP" --client-duration="$DURATION" 2>/dev/null \
        | grep '|' >> "$tmp" || echo "  run $run for $type failed" >&2
    fi
  done
  [ -s "$tmp" ] && median < "$tmp" || echo "$type: no successful runs" >&2
  rm -f "$tmp"
done
