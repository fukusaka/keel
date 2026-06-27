// ws-held.js — held-pooled WebSocket workload bench (allocator-capability measure)
//
// Drives the `/ws-held/:n/:mode` route, which holds `n` received messages
// before echoing the evicted oldest. So at steady state the server keeps `n`
// pooled payloads per connection OUTSTANDING (not returned to the pool),
// depleting the per-EventLoop freelist reserve and forcing central-allocator
// carve. The matching server profile (run the kexe with `--profile-alloc`)
// shows the per-size-class carve / miss% under that held working set.
//
// Unlike the ping-pong `ws-echo.js` (1 message outstanding), the held route
// only echoes once it holds more than `n` messages, so a 1-deep client would
// deadlock waiting for an echo that never comes until `n+1` are sent. This
// client therefore keeps a WINDOW of outstanding sends (WINDOW > n) and tops it
// back up on each received echo, sustaining throughput through the hold.
//
// Required env:
//   HOST       target host
//   PORT       target port
// Optional env:
//   WS_PATH        route incl. ring size + mode (default: /ws-held/64/chunks)
//   PAYLOAD_BYTES  message size in bytes (default: 256)
//   PAYLOAD_TYPE   text | binary (default: binary — binary frames are the ones
//                  delivered as pooled BinaryChunks, which the measurement needs)
//   WS_WINDOW      outstanding sends per VU (default: ring-size + 64; MUST exceed
//                  the route's :n or the server never echoes and the VU stalls)
//   VUS            concurrent connections (default: 50)
//   DURATION       bench duration (default: 15s)
//
// Throughput is reported by k6's built-in `ws_msgs_received` (bench-stream-one.sh
// keys the `ws` parser on it for rps). `ws_msg_rtt_ms` is emitted for p50/p99 but
// is hold-inflated (an echo returns only after `n` later sends), so treat it as a
// pipeline-depth artifact, not a latency — rps + the server's --profile-alloc dump
// are the measurement's real outputs.

import { WebSocket } from 'k6/websockets';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const WS_PATH = __ENV.WS_PATH || '/ws-held/64/chunks';
const PAYLOAD_BYTES = Number(__ENV.PAYLOAD_BYTES || 256);
const PAYLOAD_TYPE = (__ENV.PAYLOAD_TYPE || 'binary').toLowerCase();

// Auto-size the outstanding window above the route's ring so the server always
// has more than `n` messages to evict+echo. WS_WINDOW overrides.
const PATH_N = (() => {
    const m = WS_PATH.match(/\/ws-held\/(\d+)/);
    return m ? Number(m[1]) : 64;
})();
const WINDOW = Number(__ENV.WS_WINDOW || (PATH_N + 64));

const TEXT_PAYLOAD = 'x'.repeat(PAYLOAD_BYTES);
const BINARY_PAYLOAD = (() => {
    const buf = new ArrayBuffer(PAYLOAD_BYTES);
    new Uint8Array(buf).fill(0x78); // 'x'
    return buf;
})();
const PAYLOAD = PAYLOAD_TYPE === 'text' ? TEXT_PAYLOAD : BINARY_PAYLOAD;

const rttMs = new Trend('ws_msg_rtt_ms', true);

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
    insecureSkipTLSVerify: true,
};

export default function () {
    return new Promise((resolve) => {
        const url = `${__ENV.WS_SCHEME || 'ws'}://${__ENV.HOST}:${__ENV.PORT}${WS_PATH}`;
        const ws = new WebSocket(url);
        ws.binaryType = 'arraybuffer';

        const sendTs = []; // FIFO send timestamps; echoes return in send order.
        let outstanding = 0;
        let opened = false;
        let resolved = false;
        const finish = () => {
            if (!resolved) {
                resolved = true;
                resolve();
            }
        };

        const pump = () => {
            while (outstanding < WINDOW) {
                sendTs.push(Date.now());
                ws.send(PAYLOAD);
                outstanding += 1;
            }
        };

        ws.onopen = () => {
            opened = true;
            pump();
        };
        ws.onmessage = (event) => {
            const t0 = sendTs.shift();
            if (t0 !== undefined) {
                rttMs.add(Date.now() - t0);
            }
            const data = event.data;
            const len = typeof data === 'string' ? data.length : (data.byteLength || 0);
            check(len, { 'echo size correct': (n) => n === PAYLOAD_BYTES });
            outstanding -= 1;
            pump();
        };
        ws.onclose = () => {
            check(opened, { 'status 101': (v) => v === true });
            finish();
        };
        ws.onerror = () => {
            ws.close();
        };
    });
}
