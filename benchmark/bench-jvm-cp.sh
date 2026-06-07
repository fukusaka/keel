#!/usr/bin/env bash
# Resolve the JVM benchmark classpath produced by `:benchmark:writeClasspath`,
# substituting the `${REPO_ROOT}` / `${GRADLE_USER_HOME}` placeholders against
# the running host's layout, and verify each entry exists on disk.
#
# Why: `writeClasspath` writes paths anchored to two placeholders so the file
# survives rsync to a host whose repo lives elsewhere or whose Gradle user home
# differs. Bench scripts source / invoke this helper to either get a resolved
# classpath suitable for `java -cp` or fail with `JVM_CP_INVALID` early — the
# previous failure mode was a confusing `READY_TIMEOUT_7` because the JVM
# silently failed to load classes and never bound the listen port.
#
# Usage:
#   bash benchmark/bench-jvm-cp.sh resolve    # echo the resolved CP on stdout
#   bash benchmark/bench-jvm-cp.sh check      # exit 0 if every entry exists
#
# Inputs (env vars; reasonable defaults):
#   BENCH_JVM_CP_FILE  Path to the classpath file (default
#                      benchmark/build/benchmark-classpath.txt, resolved from
#                      $PWD which is expected to be the repo root).
#   GRADLE_USER_HOME   Gradle user home (default ~/.gradle).
#
# Exit codes:
#   0  OK (resolve printed CP / check found every entry).
#   2  classpath file missing or empty.
#   3  at least one entry is missing on disk (JVM_CP_INVALID).

set -euo pipefail

MODE="${1:-resolve}"
JVM_CP_FILE="${BENCH_JVM_CP_FILE:-benchmark/build/benchmark-classpath.txt}"
REPO_ROOT="$PWD"
GRADLE_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"

if [ ! -s "$JVM_CP_FILE" ]; then
    echo "JVM_CP_INVALID: classpath file missing or empty at $JVM_CP_FILE" >&2
    exit 2
fi

# Read, substitute placeholders.
RAW=$(cat "$JVM_CP_FILE")
RESOLVED=${RAW//\$\{REPO_ROOT\}/$REPO_ROOT}
RESOLVED=${RESOLVED//\$\{GRADLE_USER_HOME\}/$GRADLE_HOME}

# Verify that the *substantial* classpath entries — the compiled jars and
# resource roots — exist on disk. Treat a missing entry as `JVM_CP_INVALID` and
# fail fast so the operator sees the cause directly, instead of waiting for the
# bench harness's `READY_TIMEOUT_7` (the JVM silently ignores missing classpath
# entries and never binds the listen port, which previously presented as a
# 60-second TCP-refused timeout with no attribution).
#
# Exempt entries that Gradle lists for tasks that compile no sources (e.g. a
# project with no .java when only Kotlin is present produces an empty
# `classes/java/jvmMain` directory that is never materialised). Those paths are
# harmless to `java -cp` and are not a portability problem.
missing=0
first_missing=""
IFS=':' read -r -a entries <<< "$RESOLVED"
for entry in "${entries[@]}"; do
    if [ -z "$entry" ]; then continue; fi
    if [ -e "$entry" ]; then continue; fi
    # Harmless: empty-by-construction directories Gradle lists eagerly for
    # tasks that produced no outputs (no .java sources, no resources, etc).
    # These never materialise on disk and `java -cp` ignores them silently —
    # so a missing one is not a portability problem.
    case "$entry" in
        */classes/java/jvmMain|*/classes/java/main|*/classes/groovy/jvmMain) continue ;;
        */processedResources/jvm/main|*/processedResources/main) continue ;;
    esac
    if [ "$missing" -eq 0 ]; then
        first_missing="$entry"
        echo "JVM_CP_INVALID: at least one classpath entry is missing on disk." >&2
        echo "JVM_CP_INVALID:   repo root expected at:  $REPO_ROOT" >&2
        echo "JVM_CP_INVALID:   gradle user home:       $GRADLE_HOME" >&2
        echo "JVM_CP_INVALID:   first missing entry:    $entry" >&2
        echo "JVM_CP_INVALID: hint: build on the target host first," >&2
        echo "JVM_CP_INVALID:       e.g. ./gradlew -Pbenchmark :benchmark:writeClasspath" >&2
    fi
    missing=$((missing + 1))
done

case "$MODE" in
    resolve)
        if [ "$missing" -gt 0 ]; then exit 3; fi
        printf '%s\n' "$RESOLVED"
        ;;
    check)
        if [ "$missing" -gt 0 ]; then exit 3; fi
        ;;
    *)
        echo "usage: bench-jvm-cp.sh resolve|check" >&2
        exit 64
        ;;
esac
