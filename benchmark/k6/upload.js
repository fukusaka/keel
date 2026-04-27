// upload.js — POST /upload-stream throughput bench (request-body streaming)
//
// Drives a configurable number of virtual users that POST a fixed-size
// payload to /upload-stream and measure end-to-end RPS / latency. The
// server discards the body chunk-by-chunk via the engine's read channel,
// so RPS reflects the request-body read path's throughput more than the
// response write path (the response is a tiny "ok").
//
// Compares engines on heap pressure under streaming uploads:
//   - Pattern B (current keel-server-ktor) aggregates the body in memory
//     before the handler runs, so peak heap = N * payload during a load
//     spike.
//   - Pattern B (after Step 2 streaming refactor) and Pattern C drain
//     chunks as they arrive, so peak heap = N * (one chunk).
// Heap differences are visible in JFR / GC log captured during the run;
// k6 reports msg/s + latency only.
//
// Required env:
//   HOST       target host (e.g. 127.0.0.1)
//   PORT       target port
//   PAYLOAD_KB payload size in KB (default: 64)
// Optional env:
//   VUS        concurrent virtual users (default: 50)
//   DURATION   bench duration (default: 15s)

import http from 'k6/http';
import { check } from 'k6';

const PAYLOAD_KB = Number(__ENV.PAYLOAD_KB || 64);
const PAYLOAD = 'x'.repeat(PAYLOAD_KB * 1024);

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
};

export default function () {
    const url = `http://${__ENV.HOST}:${__ENV.PORT}/upload-stream`;
    const res = http.post(url, PAYLOAD, {
        headers: { 'Content-Type': 'application/octet-stream' },
    });
    check(res, {
        'status 200': (r) => r.status === 200,
        'echo size correct': (r) => Number(r.headers['X-Bytes-Received'] || 0) === PAYLOAD.length,
    });
}
