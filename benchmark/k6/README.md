# keel benchmark — k6 scenarios

[k6](https://k6.io) drives benchmarks for HTTP feature paths beyond what `wrk`
covers cleanly: streaming uploads (`/upload-stream`), streaming responses
(`/sse-stream`), and (after Step 1 lands) WebSocket echo (`/ws-echo`).

## Install

```bash
# macOS
brew install k6

# Linux
sudo gpg -k
sudo gpg --no-default-keyring --keyring /usr/share/keyrings/k6-archive-keyring.gpg --keyserver hkp://keyserver.ubuntu.com:80 --recv-keys C5AD17C747E3415A3642D57D77C6C491D6AC1D69
echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
sudo apt-get update
sudo apt-get install k6
```

## Scenarios

| script | endpoint | measures |
|---|---|---|
| `upload.js` | `POST /upload-stream` | request-body streaming throughput (RPS / latency); engine heap pressure visible via JFR + GC log |
| `sse.js` | `GET /sse-stream?count=N&size=M` | response-body streaming throughput (RPS / latency); engine write-path throughput |
| `ws-echo.js` | `WebSocket /ws-echo` | echo round-trip msgs/sec + p50/p99 latency. Pattern B (`ktor-keel-*`) engines fail at upgrade until Pattern B `respondUpgrade` lands; non-keel engines (`ktor-cio` / `ktor-netty` / `netty-raw` / `spring` / `vertx`) work today |

## Run

Start the bench server first (existing keel benchmark):

```bash
benchmark/build/bin/macosArm64/releaseExecutable/benchmark.kexe \
    --engine=ktor-keel-nio --port=18090
```

Then run k6:

```bash
HOST=127.0.0.1 PORT=18090 k6 run benchmark/k6/upload.js
HOST=127.0.0.1 PORT=18090 k6 run benchmark/k6/sse.js
HOST=127.0.0.1 PORT=18090 k6 run benchmark/k6/ws-echo.js
```

Tunables via env:

```bash
PAYLOAD_KB=256 VUS=100 DURATION=30s \
    HOST=127.0.0.1 PORT=18090 \
    k6 run benchmark/k6/upload.js

COUNT=500 SIZE=4096 VUS=50 DURATION=30s \
    HOST=127.0.0.1 PORT=18090 \
    k6 run benchmark/k6/sse.js

PAYLOAD_BYTES=1024 VUS=100 DURATION=30s \
    HOST=127.0.0.1 PORT=18090 \
    k6 run benchmark/k6/ws-echo.js
```

## Output

k6 prints a summary at the end (text). For machine-parseable output add
`--out json=<file>` or `--out csv=<file>`. The aggregate `bench-stream.sh`
helper parses the text summary into the same `<rps>|<p50>|<p99>` shape
that `bench-keel.sh` uses for `bench-snapshot.sh` consumption.
