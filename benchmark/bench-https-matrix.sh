#!/usr/bin/env bash
# HTTPS full-matrix benchmark: drives bench-keel.sh once per TLS backend,
# rebuilding the benchmark binary in between.
#
# The benchmark module links exactly one native TLS backend per binary
# (OpenSSL and AWS-LC declare the same libssl/libcrypto symbol names, so
# linking more than one causes symbol conflicts) — a bare `BENCH_TLS=<name>`
# loop over bench-keel.sh/bench-all.sh without an intervening rebuild only
# ever measures whichever backend the *first* build happened to link. This
# script exists so the full HTTPS matrix cannot be run that way by mistake.
#
# Usage: ./benchmark/bench-https-matrix.sh [profile]
#   profile: forwarded to bench-keel.sh (default: default)
#
# Backends benchmarked, in order: openssl, awslc, mbedtls (native — each
# rebuilds :benchmark:linkReleaseExecutable<Platform> with
# -Ptls-backend=<name>), then jsse (JVM — rebuilds :benchmark:writeClasspath).
# Skip a backend by listing only the ones you want in BENCH_HTTPS_BACKENDS.
#
# Environment variables:
#   BENCH_HTTPS_BACKENDS   Space-separated backend list (default: "openssl awslc mbedtls jsse")
#   BENCH_ENDPOINT         Forwarded to bench-keel.sh (default: /hello)
#   BENCH_RUNS             Forwarded to bench-keel.sh (default: 1; median when >1)
#   BENCH_SHUFFLE          Forwarded to bench-keel.sh (default: false)
#   BENCH_WRK_THREADS      Forwarded to bench-keel.sh (default: 4)
#   BENCH_WRK_CONNS        Forwarded to bench-keel.sh (default: 100)
#   BENCH_WRK_DURATION     Forwarded to bench-keel.sh (default: 10s)
#   BENCH_TEMP_CAPTURE     Forwarded to bench-keel.sh (default: 0)
#   BENCH_HOST_LABEL       Forwarded to bench-keel.sh (default: hostname -s)
#   BENCH_JVM_XMX          JVM heap for the compile step (default: 6g)
#
# Each backend's bench-keel.sh run gets BENCH_SCHEME=https and
# BENCH_TLS=<backend> set automatically; results are saved by bench-keel.sh
# under benchmark/results/<host>/ as usual (one file set per backend, kept
# distinct by the per-invocation timestamp).

set -uo pipefail
cd "$(dirname "$0")/.." || exit 1

BACKENDS="${BENCH_HTTPS_BACKENDS:-openssl awslc mbedtls jsse}"
PROFILE="${1:-default}"
JVM_XMX="${BENCH_JVM_XMX:-6g}"

HOST_OS="$(uname)"
case "$HOST_OS" in
    Darwin) LINK_TASK=":benchmark:linkReleaseExecutableMacosArm64" ;;
    Linux)  LINK_TASK=":benchmark:linkReleaseExecutableLinuxX64" ;;
    *) echo "unsupported host OS: $HOST_OS" >&2; exit 1 ;;
esac

echo "=== HTTPS full matrix: backends=[$BACKENDS] host=$HOST_OS ==="

for backend in $BACKENDS; do
    echo ""
    echo "=== Backend: $backend — rebuilding ==="

    ./gradlew --stop >/dev/null 2>&1 || true

    if [ "$backend" = "jsse" ]; then
        # writeClasspath's up-to-date check now tracks the -Ptls toggle and
        # its runtime dependencies as real task inputs (see benchmark/build.gradle.kts),
        # so no manual `rm -f benchmark/build/benchmark-classpath.txt` is
        # needed before this rebuild.
        if ! ./gradlew --no-configuration-cache -Ptls -Pbenchmark \
            "-Dorg.gradle.jvmargs=-Xmx${JVM_XMX} -XX:MaxMetaspaceSize=1g" \
            :benchmark:writeClasspath; then
            echo "  [skip] jsse: writeClasspath failed" >&2
            continue
        fi
    else
        if ! ./gradlew -Ptls "-Ptls-backend=${backend}" -Pbenchmark \
            "-Dorg.gradle.jvmargs=-Xmx${JVM_XMX} -XX:MaxMetaspaceSize=1g" \
            "$LINK_TASK"; then
            echo "  [skip] $backend: build failed" >&2
            continue
        fi
    fi

    echo "=== Backend: $backend — benchmarking ==="
    BENCH_SCHEME=https BENCH_TLS="$backend" ./benchmark/bench-keel.sh "$PROFILE"
done

echo ""
echo "=== HTTPS full matrix done ==="
