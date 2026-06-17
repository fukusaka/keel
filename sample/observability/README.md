# keel BufferAllocator → OpenTelemetry sample

Visual verification harness for the keel `BufferAllocator` observer hook (pluggability item 12).
The sample wires a real `PooledDirectAllocator` to OpenTelemetry via the
`keel-observability-opentelemetry` adapter and emits to an OTLP endpoint.
Verify by reading the live `keel.buffer.*` metrics in any OpenTelemetry-aware
dashboard (SigNoz, Grafana / Prometheus, Honeycomb, Datadog, …).

## Metrics emitted

| metric | kind | attributes | meaning |
|---|---|---|---|
| `keel.buffer.allocations` | Counter | `pool.name`, `path`, `size.tier` | Allocate dispatches, broken down by `hit` / `miss` / `empty` / `huge` and by tier |
| `keel.buffer.releases` | Counter | `pool.name`, `outcome`, `size.tier` | Releases, broken down by `pooled` / `discarded` / `freed` |
| `keel.buffer.allocation.size` | Histogram (bytes) | `pool.name` | Per-allocate raw byte size; raw bytes stay in the value (not the attribute) |
| `keel.buffer.pool.count` | ObservableUpDownCounter | `pool.name`, `size.tier` | Currently cached buffers per tier (sum over class indices in tier) |
| `keel.buffer.chunk.count` | ObservableUpDownCounter | `pool.name` | Currently resident chunks in the chunk arena |

Attribute matrix is pre-built once at allocator construction, so the hot path
performs no per-event allocation (matches OpenTelemetry's "recording should not
allocate memory" guidance).

## Run the sample

```sh
./gradlew -Pbenchmark :sample:runObservabilitySample
```

The sample uses the OpenTelemetry SDK's autoconfigure extension; everything is
driven by environment variables.

Minimum config (the sample assumes these defaults when nothing is set):

```sh
export OTEL_SERVICE_NAME=keel-observability-sample
export OTEL_EXPORTER_OTLP_ENDPOINT=http://localhost:4317
export OTEL_METRICS_EXPORTER=otlp
export OTEL_LOGS_EXPORTER=none
export OTEL_TRACES_EXPORTER=none
export OTEL_METRIC_EXPORT_INTERVAL=5000   # 5 s push cycle for quick visual confirmation
```

Bound the run length with `--iters=N`:

```sh
./gradlew -Pbenchmark :sample:runObservabilitySample --args="--iters=200000"
```

## Backends

### SigNoz (recommended)

SigNoz is OpenTelemetry-native; a fresh install brings up the OTLP receiver on
`localhost:4317` and the UI on `localhost:3301`. Install the official stack
from <https://signoz.io/docs/install/docker/> (it ships its own multi-service
docker-compose; the directory you clone has 10+ services so it is not bundled
here):

```sh
git clone -b main https://github.com/SigNoz/signoz.git
cd signoz/deploy
./install.sh
# Wait until: "Frontend can be accessed on http://localhost:3301"
```

Then `./gradlew -Pbenchmark :sample:runObservabilitySample` from this repo and
explore the `keel.buffer.*` metrics in SigNoz → Metrics. The `signoz/`
subdirectory of this sample exists as a placeholder for any future
SigNoz-specific dashboard JSON / preset.

### Plain OpenTelemetry Collector (for quick local verification)

For a fast "are the metrics flowing at all?" check without installing SigNoz,
the bundled `otel-collector/docker-compose.yml` runs only the OT Collector
with a `debug` (stdout) exporter — every metric the sample emits prints to the
Collector's logs:

```sh
docker compose -f sample/observability/otel-collector/docker-compose.yml up -d
./gradlew -Pbenchmark :sample:runObservabilitySample
docker compose -f sample/observability/otel-collector/docker-compose.yml logs -f otel-collector
```

You should see `keel.buffer.allocations` / `keel.buffer.releases` /
`keel.buffer.allocation.size` / `keel.buffer.pool.count` /
`keel.buffer.chunk.count` lines in the Collector logs every export cycle.

Stop with:

```sh
docker compose -f sample/observability/otel-collector/docker-compose.yml down
```

### Grafana stack (production parity, follow-up)

A `sample/observability/grafana/` directory with OT Collector + Prometheus +
Grafana + pre-built `keel-buffer-allocator.json` dashboard is the planned B4
follow-up.

### Any other OTLP-aware backend

`OTEL_EXPORTER_OTLP_ENDPOINT=http://your-otlp-host:4318` (or `:4317` for
gRPC) targets any OpenTelemetry-aware backend — Honeycomb, Datadog OT
intake, New Relic, an existing OT Collector in your environment, etc. The
sample emits standard OTLP, no vendor-specific shape.

## How the sample drives the allocator

70% of iterations allocate at the page tier (8 KiB), 20% at the tiny tier
(256 B), and 10% at the huge tier (100 KB) so each `size.tier` attribute
sees traffic. Half the allocations are held one extra iteration so the
pool gauges report a non-zero outstanding count. A short `sleep` every
2 000 iterations keeps the loop visible at human-scale rates rather than
saturating a CPU.

The release path emits POOLED / DISCARDED / FREED depending on whether the
buffer returned to its size class, hit the slot cap, or fell through to
direct backing free.
