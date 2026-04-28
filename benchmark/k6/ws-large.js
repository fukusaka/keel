// ws-large.js — WebSocket large-message round-trip bench
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
// Required env:
//   HOST       target host
//   PORT       target port
// Optional env:
//   WS_LARGE_BYTES single-message size in bytes (default: 1048576 = 1 MB)
//   VUS            concurrent connections (default: 4)
//   DURATION       bench duration (default: 15s)

import ws from 'k6/ws';
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
// usable here, unlike the small-frame ws-echo where we fall back to
// `ws_ping`. Throughput is the primary signal.
const rttMs = new Trend('ws_msg_rtt_ms', true);

export const options = {
    vus: Number(__ENV.VUS || 4),
    duration: __ENV.DURATION || '15s',
    // Bench TLS uses a self-signed cert; skip cert verification.
    insecureSkipTLSVerify: true,
};

export default function () {
    const url = `${__ENV.WS_SCHEME || 'ws'}://${__ENV.HOST}:${__ENV.PORT}/ws-echo`;
    const expectedLen = WS_LARGE_BYTES;
    const res = ws.connect(url, {}, function (socket) {
        let sendTs = 0;
        const sendOne = () => {
            sendTs = Date.now();
            socket.sendBinary(PAYLOAD);
        };
        socket.on('open', sendOne);
        socket.on('binaryMessage', (msg) => {
            rttMs.add(Date.now() - sendTs);
            const len = msg.byteLength || 0;
            check(len, { 'echo size correct': (n) => n === expectedLen });
            sendOne();
        });
        socket.on('error', () => socket.close());
    });
    check(res, { 'status 101': (r) => r && r.status === 101 });
}
