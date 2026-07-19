# Vendored third-party code

## HdrHistogram_c

Latency-percentile histogram used by the C client benchmark so its p50/p99/p99.9
match the JVM (`org.HdrHistogram`), Rust (`hdrhistogram`), Go (`hdrhistogram-go`)
and Swift (`package-histogram`) drivers.

- **Source**: <https://github.com/HdrHistogram/HdrHistogram_c>
- **License**: dual BSD-2-Clause (`LICENSE.txt`) / CC0-1.0 public domain
  (`COPYING.txt`); `hdr_histogram.c` is dedicated to the public domain in its own
  header. Both are permissive and compatible with keel's Apache-2.0 license.
- **Vendored files** (unmodified, original headers retained):
  `hdr/hdr_histogram.h`, `hdr_histogram.c`, `hdr_atomic.h`, `hdr_tests.h`,
  `hdr_malloc.h`.

Only the core histogram (init / record / value-at-percentile) is used. The
encode/decode/log helpers declared in `hdr_tests.h` are not linked.
