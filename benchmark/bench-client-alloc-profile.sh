#!/usr/bin/env bash
# bench-client-alloc-profile.sh — per-site allocation profile for ONE JVM client.
#
# Complements bench-client-alloc.sh: that gate reports the total bytes/op; this
# answers WHERE those bytes are allocated. It runs one client under JFR
# (jdk.ObjectAllocationSample, built into the JDK — no agent / dependency) and
# prints the JDK's allocation-by-site (top stack frames) and allocation-by-class
# (top allocated types) views. Use it to break down a cost the gate flags — e.g.
# the Ktor pipeline's per-request tax, or (once it lands) keel's own client.
#
# JFR allocation sampling is throttled, so the breakdown is relative attribution
# (which sites dominate), not exact bytes — bench-client-alloc.sh owns the exact
# per-op total. java-allocation-instrumenter would give exact per-site bytes at
# the cost of a -javaagent + per-allocation callback; JFR is chosen for zero
# dependencies and low overhead.
#
# Usage:
#   ./benchmark/bench-client-alloc-profile.sh <client-type> [endpoint] [requests]
#   ./benchmark/bench-client-alloc-profile.sh ktor-okhttp /hello 40000
set -euo pipefail

cd "$(dirname "$0")/.."

CLIENT="${1:?usage: bench-client-alloc-profile.sh <client-type> [endpoint] [requests]}"
ENDPOINT="${2:-/hello}"
REQUESTS="${3:-40000}"
CONNS="${BENCH_CLIENT_CONNS:-50}"
PORT="${BENCH_FIXTURE_PORT:-18830}"

mkdir -p benchmark/results
JFR="benchmark/results/alloc-profile-${CLIENT}.jfr"

benchmark/rust-bench/target/release/rust-bench --port="$PORT" >/dev/null 2>&1 &
RB=$!
trap 'kill $RB 2>/dev/null' EXIT
for _ in $(seq 1 50); do
  curl -sf -o /dev/null "http://127.0.0.1:${PORT}/hello" 2>/dev/null && break
  sleep 0.2
done

echo "resolving JVM classpath..." >&2
./gradlew --no-configuration-cache -Pbenchmark :benchmark:jvmMainClasses :benchmark:writeClasspath >/dev/null 2>&1
CP="$(./benchmark/bench-jvm-cp.sh resolve)"

echo "profiling ${CLIENT} ${ENDPOINT} (${REQUESTS} requests) under JFR..." >&2
java -XX:StartFlightRecording="settings=profile,filename=${JFR},dumponexit=true" \
  -cp "$CP" io.github.fukusaka.keel.benchmark.JvmMainKt \
  --role=client --client-type="$CLIENT" --client-target="http://127.0.0.1:${PORT}" \
  --client-endpoint="$ENDPOINT" --client-connections="$CONNS" \
  --client-warmup=1 --client-requests="$REQUESTS" 2>/dev/null | grep '|' || true

echo
echo "# allocation-by-site — ${CLIENT} ${ENDPOINT} (top stack frames)"
jfr view --width 140 allocation-by-site "$JFR"
echo
echo "# allocation-by-class — ${CLIENT} ${ENDPOINT} (top allocated types)"
jfr view --width 140 allocation-by-class "$JFR"
