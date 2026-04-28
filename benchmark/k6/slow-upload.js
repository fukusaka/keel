// slow-upload.js — POST /upload-stream at a deliberately slow rate per VU.
//
// Drives the same `/upload-stream` endpoint as `upload.js`, but inserts
// an iteration-level sleep so each VU is rate-limited rather than
// running at saturation. Many VUs × low per-VU rate × long duration
// surfaces engine behaviour under a sustained "trickle" workload — keep-
// alive connection sustain, accept-loop tail latency under near-idle
// conditions, and the cost of carrying many low-traffic sockets that
// real services see most of the time (think: many client devices each
// sending one request every few seconds).
//
// Why this is in addition to `upload.js`:
//   - upload.js measures saturation throughput. Real services almost
//     never run at saturation; the steady-state cost of an idle-ish
//     keep-alive connection is what dominates production CPU.
//   - Per-VU sleep at the iteration boundary keeps the request itself
//     full-speed (no chunked-with-delay shenanigans) but caps how often
//     the VU issues one. The total RPS is `VUS / (request_time +
//     SLOW_INTERVAL_MS/1000)`, which we can dial down to 1 RPS per VU.
//
// Required env:
//   HOST            target host
//   PORT            target port
// Optional env:
//   PAYLOAD_KB           payload size in KB (default: 1 — small body so
//                        request time stays short and SLOW_INTERVAL_MS
//                        dominates the per-VU pace)
//   SLOW_INTERVAL_MS     ms to sleep at the end of every iteration
//                        (default: 100 — i.e. ~10 RPS per VU under
//                        loopback). Set to 0 to behave like upload.js.
//   VUS                  concurrent virtual users (default: 50)
//   DURATION             bench duration (default: 15s)
//   CONNECTION_CLOSE     when "true", carry `Connection: close` per
//                        request. Forwarded by
//                        `bench-stream-one.sh slow-upload` from
//                        `BENCH_HTTP_CONNECTION_CLOSE`. Default off.
//
// What the bench output means:
//   - RPS reflects the throttled steady state, not engine throughput.
//     Compare across engines at the same SLOW_INTERVAL_MS to surface
//     idle-handling cost differences.
//   - p50 / p99 of `http_req_duration` reflect the per-request handler
//     latency. Engines whose handler keeps pretending it is at
//     saturation (busy-wait, hot-loop accept) will show worse tail
//     latency under this workload than under upload.js.

import http from 'k6/http';
import { check, sleep } from 'k6';

const PAYLOAD_KB = Number(__ENV.PAYLOAD_KB || 1);
const PAYLOAD_LEN = PAYLOAD_KB * 1024;
const PAYLOAD = 'x'.repeat(PAYLOAD_LEN);

const SLOW_INTERVAL_MS = Number(__ENV.SLOW_INTERVAL_MS || 100);

const CONNECTION_CLOSE = String(__ENV.CONNECTION_CLOSE || '').toLowerCase() === 'true';
const HEADERS = CONNECTION_CLOSE
    ? { 'Content-Type': 'application/octet-stream', 'Connection': 'close' }
    : { 'Content-Type': 'application/octet-stream' };

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
};

export default function () {
    const url = `http://${__ENV.HOST}:${__ENV.PORT}/upload-stream`;
    const res = http.post(url, PAYLOAD, { headers: HEADERS });
    check(res, {
        'status 200': (r) => r.status === 200,
        'echo size correct': (r) => Number(r.headers['X-Bytes-Received'] || 0) === PAYLOAD.length,
    });
    if (SLOW_INTERVAL_MS > 0) {
        sleep(SLOW_INTERVAL_MS / 1000);
    }
}
