// ws-large.js — WebSocket large-message round-trip bench (k6/websockets stable)
//
// Each VU opens one WebSocket connection and round-trips a single binary
// message of `WS_LARGE_BYTES` bytes (default 1 MB). The server is
// expected to echo the same bytes back; we verify the size and measure
// throughput in MB/s plus per-message RTT.
//
// Why a separate scenario from `ws-echo.js`:
//
// - ws-echo.js targets small-frame echo throughput (default 256 B,
//   tens of thousands of msgs/sec) with many concurrent VUs.
// - ws-large.js targets the *server's ability to deliver a single
//   message that exceeds the kernel send buffer* (typical SO_SNDBUF on
//   Linux ~ 200 KB). With 1 MB payloads the server has to fragment the
//   transmit across multiple write syscalls (and possibly multiple
//   WebSocket frames) and the bench picks up backpressure handling +
//   send loop correctness.
//
// Migrated to `k6/websockets` (k6 >= v1.0). The legacy `k6/ws` module's
// `socket.sendBinary(1 MB)` blocked the JS event loop for 5+ minutes
// when the server closed the connection partway through (e.g.
// zig-bench's `readSmallMessage` MessageOversize 1003 close). The
// `k6/websockets` `ws.send` is async so `onclose` / iteration end fire
// immediately on partial-write-to-closed-socket and the bench pipeline
// stays forward-progressing — verified A/B against zig-bench (clean 5 s
// run, `rc=0`) and ktor-cio JVM (steady-state 1 MB echo round trip).
//
// Required env:
//   HOST       target host
//   PORT       target port
// Optional env:
//   WS_LARGE_BYTES single-message size in bytes (default: 1048576 = 1 MB)
//   VUS            concurrent connections (default: 4)
//   DURATION       bench duration (default: 15s)

import { WebSocket } from 'k6/websockets';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const WS_LARGE_BYTES = Number(__ENV.WS_LARGE_BYTES || 1048576);

const PAYLOAD = (() => {
    const buf = new ArrayBuffer(WS_LARGE_BYTES);
    const view = new Uint8Array(buf);
    view.fill(0x78); // 'x'
    return buf;
})();

// JS-side per-message RTT (ms precision) — large messages typically push
// each round trip well over 1 ms even on loopback so ms granularity is
// usable here. Throughput is the primary signal.
const rttMs = new Trend('ws_msg_rtt_ms', true);

export const options = {
    vus: Number(__ENV.VUS || 4),
    duration: __ENV.DURATION || '15s',
    // Bench TLS uses a self-signed cert; skip cert verification.
    insecureSkipTLSVerify: true,
};

export default function () {
    return new Promise((resolve) => {
        const url = `${__ENV.WS_SCHEME || 'ws'}://${__ENV.HOST}:${__ENV.PORT}/ws-echo`;
        const expectedLen = WS_LARGE_BYTES;
        const ws = new WebSocket(url);
        ws.binaryType = 'arraybuffer';

        let sendTs = 0;
        let opened = false;
        let resolved = false;
        const finish = () => {
            if (!resolved) {
                resolved = true;
                resolve();
            }
        };

        const sendOne = () => {
            sendTs = Date.now();
            ws.send(PAYLOAD);
        };

        ws.onopen = () => {
            opened = true;
            sendOne();
        };
        ws.onmessage = (event) => {
            rttMs.add(Date.now() - sendTs);
            const data = event.data;
            const len = data && data.byteLength ? data.byteLength : (data ? data.length : 0);
            check(len, { 'echo size correct': (n) => n === expectedLen });
            sendOne();
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
