// upload.js — POST /upload-stream throughput bench (request-body streaming)
//
// Drives a configurable number of virtual users that POST a fixed-size
// payload to /upload-stream and measure end-to-end RPS / latency. The
// server discards the body chunk-by-chunk via the engine's read channel,
// so RPS reflects the request-body read path's throughput more than the
// response write path (the response is a tiny "ok").
//
// Compares engines on heap pressure under streaming uploads:
//   - Aggregating adapters buffer the body in memory before the handler
//     runs, so peak heap = N * payload during a load spike.
//   - Streaming adapters (post Step 2 refactor for `keel-server-ktor`,
//     and `pipeline-http-*` direct keel pipeline) drain chunks as they
//     arrive, so peak heap = N * (one chunk).
// Heap differences are visible in JFR / GC log captured during the run;
// k6 reports msg/s + latency only.
//
// Required env:
//   HOST            target host (e.g. 127.0.0.1)
//   PORT            target port
// Optional env:
//   UPLOAD_BYTES    payload size in bytes (overrides PAYLOAD_KB if set;
//                   accepts MB-scale values for `/upload-stream` heap-pressure
//                   bench, e.g. UPLOAD_BYTES=10485760 for 10 MB).
//                   Distinct from ws-echo.js's PAYLOAD_BYTES so the
//                   bench-stream-one.sh harness can forward both at once
//                   without an env-var name collision.
//   PAYLOAD_KB      payload size in KB (default: 64; preserved for back-compat
//                   with existing summary tables)
//   VUS             concurrent virtual users (default: 50)
//   DURATION        bench duration (default: 15s)
//   CONNECTION_CLOSE  when "true", every request carries `Connection: close`
//                     so k6 + the server tear the TCP connection down after
//                     each round-trip. Default off (HTTP/1.1 keep-alive
//                     reuses one socket per VU). Used by
//                     `bench-keepalive-compare.sh` to A/B-test how much of
//                     the throughput comes from connection reuse vs the
//                     per-request handler path.

import http from 'k6/http';
import { check } from 'k6';

// Resolve payload size: UPLOAD_BYTES wins when set, otherwise convert
// PAYLOAD_KB. Allocate the payload string once at module load — k6 shares
// it across all VU iterations so memory cost is one copy total.
const UPLOAD_BYTES = Number(__ENV.UPLOAD_BYTES || 0);
const PAYLOAD_KB = Number(__ENV.PAYLOAD_KB || 64);
const PAYLOAD_LEN = UPLOAD_BYTES > 0 ? UPLOAD_BYTES : PAYLOAD_KB * 1024;
const PAYLOAD = 'x'.repeat(PAYLOAD_LEN);

const CONNECTION_CLOSE = String(__ENV.CONNECTION_CLOSE || '').toLowerCase() === 'true';
const HEADERS = CONNECTION_CLOSE
    ? { 'Content-Type': 'application/octet-stream', 'Connection': 'close' }
    : { 'Content-Type': 'application/octet-stream' };

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
    // Bench TLS uses a self-signed cert; skip cert verification.
    insecureSkipTLSVerify: true,
};

export default function () {
    const url = `${__ENV.SCHEME || 'http'}://${__ENV.HOST}:${__ENV.PORT}/upload-stream`;
    const res = http.post(url, PAYLOAD, { headers: HEADERS });
    check(res, {
        'status 200': (r) => r.status === 200,
        'echo size correct': (r) => Number(r.headers['X-Bytes-Received'] || 0) === PAYLOAD.length,
    });
}
