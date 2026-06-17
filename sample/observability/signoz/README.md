# SigNoz backend for the keel BufferAllocator observability sample

SigNoz ships an OpenTelemetry-native dashboard with an OTLP receiver on
`localhost:4317`. Install the official multi-service stack — it is not
bundled here because the upstream `docker-compose.yaml` brings up 10+
services and would dwarf the sample directory:

```sh
git clone -b main https://github.com/SigNoz/signoz.git
cd signoz/deploy
./install.sh
# Wait for: "Frontend can be accessed on http://localhost:3301"
```

Then from this repo:

```sh
./gradlew -Pbenchmark :sample:runObservabilitySample
```

Open <http://localhost:3301> → Metrics, search for `keel.buffer`, and the
five instruments emitted by the sample appear:

- `keel.buffer.allocations` — chart by `path` and `size.tier`.
- `keel.buffer.releases` — chart by `outcome` and `size.tier`.
- `keel.buffer.allocation.size` — histogram of byte sizes per allocate.
- `keel.buffer.pool.count` — current cached buffers per `size.tier`.
- `keel.buffer.chunk.count` — currently resident chunks in the arena.

This directory is a placeholder for any future SigNoz-specific dashboard
JSON / preset. For now SigNoz's autodiscovery makes the metrics visible
without preset configuration.

See `sample/observability/README.md` for the metrics taxonomy and a
lighter "OT Collector with debug exporter" alternative that prints the
metrics to stdout for quick sanity checks.
