#!/usr/bin/env bash
# bench-temp.sh — portable, no-sudo CPU temperature reader for the bench scripts.
#
# Sourced (not executed) by bench-one.sh / bench-stream-one.sh / bench-remote.sh.
# Temperature capture is opt-in per run via BENCH_TEMP_CAPTURE=1; when disabled
# the helpers are no-ops so the default result-row format is unchanged.
#
# Why this exists: a bench cell that looks like a regression can instead be the
# host warming up over a long sweep. Recording the CPU temperature at the start
# and end of each engine's measured window lets a reviewer tell a real engine
# delta from a thermal-drift artifact (the ws-deflate "19K->10K" investigation
# turned out to be neither thermal nor a regression, but the only way to rule
# thermal out was to read the temperature — so make that a first-class capture).
#
# Both sensors below are readable without sudo and without installing anything:
#   - macOS (Darwin): IORegistry `VirtualTemperature` — a SoC-proximate virtual
#     sensor in centi-degrees C. Coarser than a die sensor but always present
#     and monotonic enough to show drift. (`Temperature` is the battery pack,
#     too lagged to be useful here.)
#   - Linux: the first CPU-package `thermal_zone` (type x86_pkg_temp / cpu), or
#     failing that a CPU hwmon sensor (coretemp / k10temp / cpu_thermal)
#     `temp1_input`, both in milli-degrees C.
#
# `read_temp_c [host]` prints one decimal degrees C, or nothing when no sensor
# is found (callers must tolerate an empty string). With a host argument it
# reads over ssh, so bench-remote.sh can record the server host and the wrk
# client host separately. The reader is a single-quoted POSIX snippet shipped
# verbatim to a local `sh -c` or a remote `ssh host` shell, so it survives a
# zsh login shell on the far side (no bash-only `declare -f`).

# POSIX snippet evaluated locally or over ssh. Single-quoted on purpose: the
# `$z` / `$h` / `$(cat ...)` inside must be expanded by the *target* shell, not
# the shell that sources this file.
# shellcheck disable=SC2016
BENCH_TEMP_SNIPPET='
if [ "$(uname)" = Darwin ]; then
  ioreg -r -k VirtualTemperature 2>/dev/null \
    | awk -F"= " "/\"VirtualTemperature\"/{printf \"%.1f\n\", \$2/100; exit}"
else
  for z in /sys/class/thermal/thermal_zone*; do
    [ -r "$z/type" ] || continue
    case "$(cat "$z/type" 2>/dev/null)" in
      *x86_pkg_temp*|*cpu*|*CPU*)
        t="$(cat "$z/temp" 2>/dev/null)"
        [ -n "$t" ] && awk "BEGIN{printf \"%.1f\n\", $t/1000}" && exit 0 ;;
    esac
  done
  for h in /sys/class/hwmon/hwmon*; do
    case "$(cat "$h/name" 2>/dev/null)" in
      coretemp|k10temp|cpu_thermal)
        [ -r "$h/temp1_input" ] \
          && awk "BEGIN{printf \"%.1f\n\", $(cat "$h/temp1_input" 2>/dev/null)/1000}" \
          && exit 0 ;;
    esac
  done
fi
'

# read_temp_c [host] — echo current CPU temperature in C (one decimal), or "".
read_temp_c() {
    if [ -n "${1:-}" ]; then
        ssh "$1" "$BENCH_TEMP_SNIPPET" 2>/dev/null
    else
        sh -c "$BENCH_TEMP_SNIPPET" 2>/dev/null
    fi
}

# format_temp_delta START END — render "SS.S->EE.SC(d+N.N)" for a result row,
# or "" when capture was disabled / no sensor (either bound empty). Uses `->`
# and `d` rather than arrow/Delta glyphs so the field stays ASCII and safe to
# pipe-split.
format_temp_delta() {
    local start="$1" end="$2"
    [ -n "$start" ] && [ -n "$end" ] || { printf ''; return; }
    awk -v s="$start" -v e="$end" 'BEGIN{printf "%.1f->%.1fC(d%+.1f)", s, e, e-s}'
}
