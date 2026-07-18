#!/usr/bin/env bash
# bench-client-alloc.sh — per-request allocation baseline + regression gate.
#
# Measures true bytes/op (whole-JVM getTotalThreadAllocatedBytes, the counter
# JMH's gc.alloc.rate.norm reads on Java 17+) for the JVM reference clients via
# bench-client.sh, and either writes a baseline or checks the current run against
# it, failing (exit 1) when any client's allocation deviates beyond a tolerance.
# This is the Ktor-style allocation forcing function: allocation is keel's core
# concern, so a regression must break the build rather than pass silently.
#
# SCOPE NOTE: the gate's primary target is keel's OWN HTTP client — the thing
# whose allocation keel controls. Until that lands it guards the reference
# baseline against dependency / JDK allocation drift (a real but secondary use)
# and exercises the mechanism. bytes/op is a JVM/GC metric, so native reference
# clients (rust / go / c / swift) are out of scope.
#
# Allocation is CPU-independent but JDK/library-version dependent, so the tracked
# baseline is seeded on one host (JDK 21) and the tolerance absorbs cross-host
# JDK differences; re-seed with BENCH_ALLOC_WRITE=1 when JDK / client deps change.
#
# Usage:
#   ./benchmark/bench-client-alloc.sh              # check against the baseline
#   BENCH_ALLOC_WRITE=1 ./benchmark/bench-client-alloc.sh   # (re)write the baseline
#
# Env:
#   BENCH_ALLOC_TYPES      JVM clients to gate (default 6 pooling clients;
#                          ktor-cio excluded — KTOR-6503 churn breaks steady state)
#   BENCH_ALLOC_ENDPOINTS  endpoints (default "/hello /large")
#   BENCH_ALLOC_TOLERANCE  percent deviation that fails the gate (default 15)
#   BENCH_ALLOC_WRITE=1    write the baseline instead of checking
#   plus bench-client.sh env (BENCH_CLIENT_CONNS, BENCH_RUNS, BENCH_FIXTURE_PORT)
set -euo pipefail

cd "$(dirname "$0")/.."

BASELINE="benchmark/client-alloc-baseline.txt"
TOLERANCE="${BENCH_ALLOC_TOLERANCE:-15}"
TYPES="${BENCH_ALLOC_TYPES:-java okhttp apache5 ktor-java ktor-okhttp ktor-apache5}"
ENDPOINTS="${BENCH_ALLOC_ENDPOINTS:-/hello /large}"

tmp="$(mktemp)"
trap 'rm -f "$tmp"' EXIT

for ep in $ENDPOINTS; do
  BENCH_CLIENT_TYPES="$TYPES" \
    BENCH_CLIENT_CONNS="${BENCH_CLIENT_CONNS:-50}" \
    BENCH_CLIENT_ENDPOINT="$ep" \
    BENCH_RUNS="${BENCH_RUNS:-3}" \
    BENCH_FIXTURE_PORT="${BENCH_FIXTURE_PORT:-18820}" \
    ./benchmark/bench-client.sh 2>/dev/null \
    | grep -E '^[a-z0-9].*\|' \
    | while IFS='|' read -r name _rps _p50 _p99 _p999 _max bop _err; do
        echo "$name $bop"
      done >> "$tmp"
done

if [ "${BENCH_ALLOC_WRITE:-0}" = 1 ]; then
  sort "$tmp" > "$BASELINE"
  echo "# wrote allocation baseline ($BASELINE):"
  cat "$BASELINE"
  exit 0
fi

if [ ! -f "$BASELINE" ]; then
  echo "no baseline at $BASELINE — seed it with BENCH_ALLOC_WRITE=1" >&2
  exit 1
fi

python3 - "$BASELINE" "$tmp" "$TOLERANCE" <<'PY'
import sys
base_path, cur_path, tol = sys.argv[1], sys.argv[2], float(sys.argv[3])
base = {}
for line in open(base_path):
    line = line.strip()
    if not line or line.startswith('#'):
        continue
    k, v = line.split()
    base[k] = float(v)
failed = False
rows = []
for line in open(cur_path):
    line = line.strip()
    if not line:
        continue
    k, v = line.split()
    v = float(v)
    if k not in base:
        rows.append(("NEW ", k, v, None, None))
        continue
    b = base[k]
    diff = (v - b) / b * 100.0
    if abs(diff) > tol:
        failed = True
        rows.append(("FAIL", k, v, b, diff))
    else:
        rows.append(("ok  ", k, v, b, diff))
w = max((len(k) for _, k, *_ in rows), default=8)
print(f"# allocation gate (tolerance +/-{tol:.0f}%)")
for flag, k, v, b, diff in sorted(rows, key=lambda r: r[1]):
    if b is None:
        print(f"{flag} {k:<{w}} {v:>10.0f} B/op  (no baseline)")
    else:
        print(f"{flag} {k:<{w}} {v:>10.0f} B/op  vs {b:>10.0f}  ({diff:+.1f}%)")
sys.exit(1 if failed else 0)
PY
