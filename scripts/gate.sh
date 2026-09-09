#!/usr/bin/env bash
#
# gate.sh — run the PR pre-merge gate on one remote host (or both).
#
# Two hosts are needed for full coverage. A macOS host builds macosArm64, which
# CI does not build at all; a Linux host builds linuxX64, which a macOS host
# cannot cross-compile. Neither host covers the other's target.
#
# Every element below is load-bearing and was added after a failure, which is
# why this is a script and not a command line in a document:
#
#   --stop before the run   A daemon's heap accumulates across a long session
#                           until the compiler OOMs; the ssh call then hangs
#                           with no output.
#   timeout                 Turns that hang into a non-zero exit instead of an
#                           unbounded wait.
#   remote log + </dev/null Killing gradle does not free the ssh pipe if the
#                           daemon inherited it, so ssh keeps running after the
#                           timeout fires. Writing to a file on the remote and
#                           closing stdin releases it.
#   -Xmx6g                  The 4g default OOMs the compiler on keel-core, and
#                           an interrupted incremental JS compile then fails
#                           with "cannot find external signature" until the
#                           module's build dir is removed.
#   archive test-results    A rerun overwrites the failing run's XML, and
#   unconditionally         rerunning is the natural next move. Archiving only
#                           on failure loses the evidence to that same reflex.
#                           Fed to tar through -print0/--null rather than as
#                           arguments: the tree holds ~15k such files and the
#                           argument form fails with E2BIG. Its stderr goes to
#                           the log, not to /dev/null, because that failure is
#                           exactly what hid this one.
#   aggregate tasks         Enumerating modules misses satellite modules
#   and no --tests filter   (keel-tls-*, keel-testing-*); a filter hides
#                           pre-existing failures the gate exists to catch.
#
# usage:
#   KEEL_GATE_MAC_HOST=<host>   scripts/gate.sh mac
#   KEEL_GATE_LINUX_HOST=<host> scripts/gate.sh linux
#   KEEL_GATE_MAC_HOST=<host> KEEL_GATE_LINUX_HOST=<host> scripts/gate.sh both
#
# env:
#   KEEL_GATE_MAC_HOST     ssh target that builds macosArm64   (required for mac)
#   KEEL_GATE_LINUX_HOST   ssh target that builds linuxX64     (required for linux)
#   KEEL_GATE_MAC_DIR      remote workdir  (default: ~/prj/keel-work/keel)
#   KEEL_GATE_LINUX_DIR    remote workdir  (default: ~/prj/keel-work/keel)
#   KEEL_GATE_TIMEOUT      seconds         (default: 900)
#   KEEL_GATE_JVMARGS      daemon args     (default: -Xmx6g -XX:MaxMetaspaceSize=1g)
#   KEEL_GATE_NO_SYNC      set to 1 to skip rsync (re-run on an already-synced tree)
#   KEEL_GATE_RSYNC_DELETE set to 1 to add --delete --delete-excluded. Plain -az
#                          leaves a file the local tree deleted in place on the
#                          remote, where it can still be compiled. Use it when a
#                          remote-only compile error names a file you removed.
#
# Exits non-zero if any host's gate fails, after printing the tail of that
# host's log and the path of its archived test-results.

set -uo pipefail

MODE="${1:?usage: gate.sh <mac|linux|both>}"
TIMEOUT="${KEEL_GATE_TIMEOUT:-900}"
JVMARGS="${KEEL_GATE_JVMARGS:--Xmx6g -XX:MaxMetaspaceSize=1g}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

# The exclude list is shared with scripts/bench-sync.sh; one copy, one place to
# change when a new generated directory appears.
EXCLUDES="${REPO_ROOT}/scripts/rsync-excludes.txt"

# Source sets differ by host because the intermediate ones a host cannot build
# are not merely empty, they fail. detektTestSources is separate from the rest
# because detekt's KMP support builds no detektMetadata<X>Test task, so no other
# task lints commonTest / nativeTest / macosTest / linuxTest / appleTest.
MAC_TASKS="detektMetadataCommonMain detektMetadataNativeMain detektMetadataAppleMain \
detektMetadataMacosMain detektJvmMain detektMacosArm64Main detektJsMain detektTestSources \
jvmTest macosArm64Test jsNodeTest dokkaGeneratePublicationHtml"

LINUX_TASKS="detektMetadataCommonMain detektMetadataNativeMain detektMetadataLinuxMain \
detektJvmMain detektLinuxX64Main detektJsMain detektTestSources \
jvmTest linuxX64Test jsNodeTest dokkaGeneratePublicationHtml"

# A non-interactive zsh reads ~/.zshenv but not ~/.zshrc, so the Homebrew and
# sdkman entries a macOS host puts in ~/.zshrc are absent here and have to be
# named. A Linux host needs nothing.
MAC_PRELUDE='source ~/.sdkman/bin/sdkman-init.sh; export PATH=/opt/homebrew/bin:/opt/homebrew/sbin:$PATH;'

run_one() {
    local label="$1" host="$2" dir="$3" tasks="$4" prelude="$5"
    local stamp; stamp="$(date +%Y%m%d-%H%M%S)"
    local log="/tmp/keel-gate-${label}.log"
    local exitf="/tmp/keel-gate-${label}.exit"
    local arch="/tmp/keel-gate-${label}-${stamp}.tgz"

    echo "=== gate: ${label} (${host}) ==="

    if [ "${KEEL_GATE_NO_SYNC:-0}" != "1" ]; then
        local del=()
        [ "${KEEL_GATE_RSYNC_DELETE:-0}" = "1" ] && del=(--delete --delete-excluded)
        echo "--- rsync${del:+ (--delete)}"
        # ${del[@]+...} rather than "${del[@]}": bash 3.2, which is what /bin/bash
        # is on macOS, treats an empty array as unset under set -u and aborts.
        rsync -az ${del[@]+"${del[@]}"} --exclude-from="$EXCLUDES" "${REPO_ROOT}/" "${host}:${dir}/" || {
            echo "ERROR: rsync to ${host} failed" >&2; return 1; }
    fi

    echo "--- gradle (timeout ${TIMEOUT}s)"
    # The whole remote command is one quoted string so the local shell does not
    # expand $PATH, $?, or the log paths. --stop is followed by ';' rather than
    # '&&' so that having no daemon to stop does not skip the gate. The archive
    # is taken whether or not the gate passed, before anything can rerun.
    # ${dir} is deliberately unquoted so the remote shell expands a leading ~.
    # Quoting it (either way) makes cd fail on the default path.
    ssh "$host" "cd ${dir} && ${prelude} \
        ./gradlew --stop >/dev/null 2>&1; \
        timeout ${TIMEOUT} ./gradlew -Ptls -Pbenchmark \
            -Dorg.gradle.jvmargs='${JVMARGS}' \
            ${tasks} > '${log}' 2>&1 </dev/null; \
        echo \$? > '${exitf}'; \
        find . -path '*/build/test-results/*' -name '*.xml' -print0 2>/dev/null \
            | tar czf '${arch}' --null -T - >> '${log}' 2>&1 \
            || echo 'gate.sh: archiving test-results failed (see above)' >> '${log}'; \
        true" </dev/null

    local code
    code="$(ssh "$host" "cat '${exitf}' 2>/dev/null" </dev/null)"
    code="${code:-255}"

    if [ "$code" -eq 0 ]; then
        echo "--- ${label}: PASS"
        return 0
    fi

    echo "--- ${label}: FAIL (exit ${code}$([ "$code" -eq 124 ] && echo ' = timeout, daemon hang'))" >&2
    ssh "$host" "tail -40 '${log}'" </dev/null >&2
    echo "--- test-results archived on ${host}: ${arch}" >&2
    echo "--- full log on ${host}: ${log}" >&2
    return 1
}

rc=0
case "$MODE" in
    mac|linux|both) ;;
    *) echo "usage: gate.sh <mac|linux|both>" >&2; exit 2 ;;
esac

if [ "$MODE" = "mac" ] || [ "$MODE" = "both" ]; then
    : "${KEEL_GATE_MAC_HOST:?KEEL_GATE_MAC_HOST is required (ssh target that builds macosArm64)}"
    run_one mac "$KEEL_GATE_MAC_HOST" "${KEEL_GATE_MAC_DIR:-~/prj/keel-work/keel}" \
        "$MAC_TASKS" "$MAC_PRELUDE" || rc=1
fi

if [ "$MODE" = "linux" ] || [ "$MODE" = "both" ]; then
    : "${KEEL_GATE_LINUX_HOST:?KEEL_GATE_LINUX_HOST is required (ssh target that builds linuxX64)}"
    # Both hosts run even if the first failed: a gate that stops at the first
    # host hides the other host's failures for a round, which is the cost this
    # gate exists to avoid.
    run_one linux "$KEEL_GATE_LINUX_HOST" "${KEEL_GATE_LINUX_DIR:-~/prj/keel-work/keel}" \
        "$LINUX_TASKS" "" || rc=1
fi

exit $rc
