#!/usr/bin/env bash
# bench-client.sh — HTTP *client* benchmark driver (keel-client as the SUT).
#
# The inverse of bench-one.sh / bench-remote.sh: instead of an external load
# generator (wrk / k6) driving a keel *server*, here the process under test is
# an HTTP *client* driving a fixture server, and we measure the client's
# throughput / latency / per-request allocation. Methodology follows common
# HTTP-client-benchmark practice (Ktor client-benchmarks, undici, OkHttp
# MockWebServer, h2load, wrk2): a trivial loopback fixture so the client is the
# component measured, reference-client A/B (Java HttpClient, Ktor CIO),
# HdrHistogram latency, and bytes/op as a first-class metric.
#
# This first increment runs the LOOPBACK tier (in-process keel server fixture,
# started by the harness itself). The MACRO tier (real-NIC two-host, fixture
# on a separate host, tc netem RTT) is env-var-driven and lands in a later
# increment — see "macro tier" below.
#
# Usage:
#   ./benchmark/bench-client.sh
#
# Env vars:
#   BENCH_CLIENT_TYPES   space list of client drivers (default "java ktor-cio";
#                        "keel" pending the standalone keel-client-http, 12b)
#   BENCH_CLIENT_ENDPOINT  fixture path (default /hello; /large for throughput)
#   BENCH_CLIENT_CONNS   concurrent connections / pool size (default 50)
#   BENCH_CLIENT_WARMUP  warm-up seconds, discarded (default 3)
#   BENCH_CLIENT_DURATION measurement seconds (default 10)
#   BENCH_FIXTURE_ENGINE in-process fixture engine (default server-http-nio)
#   BENCH_RUNS           runs per client, median reported (default 3)
#
# Methodology guardrails (coordinated omission, SUT isolation):
#   - SUT isolation is TWO-sided: the fixture server must have CPU headroom AND
#     the client host must not be saturated (< ~85% CPU), or the number is a
#     fake plateau. This loopback tier shares a host, so watch total CPU and
#     keep the fixture engine fast (server-http-nio) relative to the client.
#   - Latency here is CLOSED-loop (coordinated-omission susceptible); the
#     open-loop constant-rate mode (CO-corrected p99.9) is a later increment.
#   - Record BENCH_RUNS>=3 median, not a single run, for any cell kept in docs.
set -euo pipefail

cd "$(dirname "$0")/.."

TYPES="${BENCH_CLIENT_TYPES:-java ktor-cio}"
ENDPOINT="${BENCH_CLIENT_ENDPOINT:-/hello}"
CONNS="${BENCH_CLIENT_CONNS:-50}"
WARMUP="${BENCH_CLIENT_WARMUP:-3}"
DURATION="${BENCH_CLIENT_DURATION:-10}"
FIXTURE="${BENCH_FIXTURE_ENGINE:-server-http-nio}"
RUNS="${BENCH_RUNS:-3}"

echo "Resolving JVM classpath (writeClasspath is config-cache-incompatible; using --no-configuration-cache)..." >&2
./gradlew --no-configuration-cache -Pbenchmark :benchmark:jvmMainClasses :benchmark:writeClasspath >/dev/null 2>&1
CP="$(./benchmark/bench-jvm-cp.sh resolve)"

echo "# client bench: endpoint=$ENDPOINT conns=$CONNS warmup=${WARMUP}s duration=${DURATION}s runs=$RUNS fixture=$FIXTURE"
echo "# format: <client><endpoint>|<req/s>|<p50ms>|<p99ms>|<p99.9ms>|<maxms>|<bytes/op>|<errors>"

median() { sort -t'|' -k2 -n | awk -F'|' '{a[NR]=$0} END{print a[int((NR+1)/2)]}'; }

for type in $TYPES; do
  tmp="$(mktemp)"
  for run in $(seq 1 "$RUNS"); do
    java -cp "$CP" io.github.fukusaka.keel.benchmark.JvmMainKt \
      --role=client --client-type="$type" --fixture-engine="$FIXTURE" \
      --client-endpoint="$ENDPOINT" --client-connections="$CONNS" \
      --client-warmup="$WARMUP" --client-duration="$DURATION" 2>/dev/null \
      | grep '|' >> "$tmp" || echo "  run $run for $type failed" >&2
    sleep 2
  done
  median < "$tmp"
  rm -f "$tmp"
done

# --- macro tier (later increment) ---
# Real-NIC two-host: run the harness with --client-target=http://<fixture-host-ip>:<port>
# from a client host separate from the fixture server host, add `tc qdisc … netem`
# RTT on the path, and verify server-side CPU headroom. Driven by BENCH_CLIENT_TARGET
# + a reachability pre-flight, mirroring bench-remote.sh. Not wired in this increment.
