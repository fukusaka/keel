#!/usr/bin/env bash
#
# bench-sync.sh — sync the tree to a bench host and build what a sweep needs.
#
# The build set is always three artefacts, not one: bench-all.sh and
# bench-stream-all.sh call preflight_check_primary_binaries and refuse to start
# if the host-appropriate native kexe, the JVM classpath file, or the JS
# production bin is missing. A sweep that silently skipped the JS engines once
# left every Node.js row in the status tables marked 要再計測.
#
# --no-configuration-cache is required, on every host: :benchmark:writeClasspath
# cannot be stored in the configuration cache (measured 2026-09-08 on Linux and
# previously on macOS — "error writing value of type 'kotlin.SynchronizedLazyImpl'"
# plus DefaultSourceSet / JavaCompile, 4 problems). The rule file used to document
# this for the macOS host only; the Linux command as written there failed.
#
# The classpath file is written with ${REPO_ROOT} / ${GRADLE_USER_HOME}
# placeholders that are expanded on the host that runs the bench, so it must be
# generated on that host — copying one between hosts leaves the placeholders in
# place and every JVM engine fails its readiness timeout.
#
# usage:
#   KEEL_BENCH_MAC_HOST=<host>   scripts/bench-sync.sh mac   [--native-refs]
#   KEEL_BENCH_LINUX_HOST=<host> scripts/bench-sync.sh linux [--native-refs]
#
# env:
#   KEEL_BENCH_MAC_HOST     ssh target that builds macosArm64   (required for mac)
#   KEEL_BENCH_LINUX_HOST   ssh target that builds linuxX64     (required for linux)
#   KEEL_BENCH_MAC_DIR      remote workdir  (default: ~/prj/keel-work/keel)
#   KEEL_BENCH_LINUX_DIR    remote workdir  (default: ~/prj/keel-work/keel)
#   KEEL_BENCH_NO_SYNC      set to 1 to build without syncing first
#
# --native-refs additionally rebuilds the non-Kotlin reference servers, whose
# artefacts the sync excludes because they are platform-specific. Only rust-bench
# and go-bench are covered here; the zig-bench and swift-bench server builds are
# not scripted (their client variants are built on demand by bench-client.sh).
#
# This does not run a sweep. Run bench-keel.sh / bench-all.sh on the host after.

set -uo pipefail

MODE="${1:?usage: bench-sync.sh <mac|linux> [--native-refs]}"
NATIVE_REFS=0
[ "${2:-}" = "--native-refs" ] && NATIVE_REFS=1
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
EXCLUDES="${REPO_ROOT}/scripts/rsync-excludes.txt"

# A non-interactive zsh reads ~/.zshenv but not ~/.zshrc, so a macOS host's
# Homebrew and sdkman entries have to be named here. A Linux host needs nothing.
MAC_PRELUDE='source ~/.sdkman/bin/sdkman-init.sh; export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:$PATH;'

case "$MODE" in
    mac)   : "${KEEL_BENCH_MAC_HOST:?KEEL_BENCH_MAC_HOST is required (ssh target that builds macosArm64)}"
           HOST="$KEEL_BENCH_MAC_HOST"; DIR="${KEEL_BENCH_MAC_DIR:-~/prj/keel-work/keel}"
           NATIVE_TASK=":benchmark:linkReleaseExecutableMacosArm64"; PRELUDE="$MAC_PRELUDE" ;;
    linux) : "${KEEL_BENCH_LINUX_HOST:?KEEL_BENCH_LINUX_HOST is required (ssh target that builds linuxX64)}"
           HOST="$KEEL_BENCH_LINUX_HOST"; DIR="${KEEL_BENCH_LINUX_DIR:-~/prj/keel-work/keel}"
           NATIVE_TASK=":benchmark:linkReleaseExecutableLinuxX64"; PRELUDE="" ;;
    *)     echo "usage: bench-sync.sh <mac|linux> [--native-refs]" >&2; exit 2 ;;
esac

if [ "${KEEL_BENCH_NO_SYNC:-0}" != "1" ]; then
    echo "--- rsync → ${HOST}"
    rsync -az --exclude-from="$EXCLUDES" "${REPO_ROOT}/" "${HOST}:${DIR}/" || {
        echo "ERROR: rsync to ${HOST} failed" >&2; exit 1; }
fi

echo "--- build (native + classpath + js)"
# ${DIR} unquoted so the remote shell expands a leading ~.
ssh "$HOST" "cd ${DIR} && ${PRELUDE} \
    ./gradlew --no-configuration-cache -Pbenchmark \
        ${NATIVE_TASK} :benchmark:writeClasspath :benchmark:compileProductionExecutableKotlinJs" </dev/null || {
    echo "ERROR: gradle build failed on ${HOST}" >&2; exit 1; }

if [ "$NATIVE_REFS" = "1" ]; then
    echo "--- non-Kotlin reference servers (rust-bench, go-bench)"
    ssh "$HOST" "cd ${DIR}/benchmark/rust-bench && cargo build --release" </dev/null || exit 1
    ssh "$HOST" "cd ${DIR}/benchmark/go-bench && go build -o go-bench ." </dev/null || exit 1
fi

echo "--- ${MODE}: ready. Run the sweep on ${HOST}, then ./benchmark/bench-pull.sh <host>"
