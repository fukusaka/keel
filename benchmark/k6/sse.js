// sse.js — GET /sse-stream throughput bench (response-body streaming)
//
// Drives a configurable number of virtual users that open an HTTP/1.1
// chunked-transfer GET to /sse-stream?count=N&size=M. The server emits
// N SSE-style frames of M bytes each; the client reads the full
// response. RPS reflects the response-body write path's throughput
// (the request side is a tiny header).
//
// k6's HTTP module reads the full response by default — sufficient
// for measuring response-stream throughput (msgs delivered + body
// bytes / s). For per-frame latency one would need k6's WebSocket /
// raw-tcp loops; SSE-as-HTTP is good enough for engine-level write
// throughput comparison.
//
// Required env:
//   HOST    target host
//   PORT    target port
// Optional env:
//   COUNT       SSE frame count per request (default: 100)
//   SIZE        bytes of payload per frame (default: 1024)
//   VUS         concurrent virtual users (default: 50)
//   DURATION    bench duration (default: 15s)

import http from 'k6/http';
import { check } from 'k6';

const COUNT = Number(__ENV.COUNT || 100);
const SIZE = Number(__ENV.SIZE || 1024);

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
};

export default function () {
    const url = `http://${__ENV.HOST}:${__ENV.PORT}/sse-stream?count=${COUNT}&size=${SIZE}`;
    const res = http.get(url);
    check(res, {
        'status 200': (r) => r.status === 200,
        'body size matches': (r) => {
            // Each frame: "data: " (6) + payload (SIZE) + "\n\n" (2)
            const expected = COUNT * (6 + SIZE + 2);
            return r.body && r.body.length === expected;
        },
    });
}
