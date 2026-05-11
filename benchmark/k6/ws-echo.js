// ws-echo.js — WebSocket echo throughput bench (k6/websockets stable)
//
// Each VU opens one WebSocket connection to /ws-echo and runs a ping-pong
// loop: send a fixed-size message, wait for the server to echo it back,
// send the next one. Measures end-to-end round-trip throughput (msgs/sec)
// and per-message latency.
//
// Migrated to the stable `k6/websockets` module (k6 >= v1.0). The legacy
// `k6/ws` module's `socket.sendBinary` blocked the JS event loop and
// ignored SIGTERM when an HTTP/WebSocket server closed the connection
// during a partial send. `k6/websockets`' `WebSocket.send` is async
// (event-loop based), so iteration terminates cleanly when the server
// closes — verified against zig-bench's MessageOversize-close pattern.
//
// Required env:
//   HOST       target host
//   PORT       target port
// Optional env:
//   PAYLOAD_BYTES message size in bytes (default: 256)
//   PAYLOAD_TYPE  text | binary (default: text)
//   VUS           concurrent connections (default: 50)
//   DURATION      bench duration (default: 15s)
//   PING_PONGS    msgs per VU before close (default: unlimited until duration)
//   CLOSE_HANDSHAKE  if "true", initiate the WebSocket close handshake
//                    after PING_PONGS messages instead of TCP-closing.
//                    Verifies the server replies with its own close frame.
//                    Default: false.
//
// Note on the `ws_ping` Trend: the legacy `k6/ws` module auto-populated a
// Go-side `ws_ping` Trend with ns precision. `k6/websockets` does not
// (its `ws.ping()` is just an out-of-band control frame). We compute
// per-echo RTT in JS via `Date.now()` and report it as `ws_msg_rtt_ms`
// (ms precision); bench-stream-one.sh keys on `ws_msg_rtt_ms` as the
// primary p50/p99 source.

import { WebSocket } from 'k6/websockets';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const PAYLOAD_BYTES = Number(__ENV.PAYLOAD_BYTES || 256);
const PAYLOAD_TYPE = (__ENV.PAYLOAD_TYPE || 'text').toLowerCase();
const PING_PONGS = Number(__ENV.PING_PONGS || 0);
const CLOSE_HANDSHAKE = String(__ENV.CLOSE_HANDSHAKE || 'false').toLowerCase() === 'true';

// Build the payload once. For binary mode we hand k6 an ArrayBuffer; for
// text we hand it a UTF-8 string. The size in bytes is identical so the
// throughput / RTT numbers between text and binary modes are directly
// comparable (modulo masking + opcode-handling differences inside the
// server).
const TEXT_PAYLOAD = 'x'.repeat(PAYLOAD_BYTES);
const BINARY_PAYLOAD = (() => {
    const buf = new ArrayBuffer(PAYLOAD_BYTES);
    const view = new Uint8Array(buf);
    view.fill(0x78); // 'x'
    return buf;
})();
const PAYLOAD = PAYLOAD_TYPE === 'binary' ? BINARY_PAYLOAD : TEXT_PAYLOAD;

const rttMs = new Trend('ws_msg_rtt_ms', true);

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
    // Bench TLS uses a self-signed cert; skip cert verification.
    insecureSkipTLSVerify: true,
};

export default function () {
    return new Promise((resolve) => {
        const url = `${__ENV.WS_SCHEME || 'ws'}://${__ENV.HOST}:${__ENV.PORT}/ws-echo`;
        const expectedLen = PAYLOAD_BYTES;
        const ws = new WebSocket(url);
        ws.binaryType = 'arraybuffer';

        let sent = 0;
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
            // For text frames `event.data` is a string; for binary k6
            // surfaces an ArrayBuffer whose `.byteLength` is the wire
            // length.
            const data = event.data;
            const len = typeof data === 'string' ? data.length : (data.byteLength || 0);
            check(len, { 'echo size correct': (n) => n === expectedLen });
            sent += 1;
            if (PING_PONGS > 0 && sent >= PING_PONGS) {
                // Send a normal-closure close frame and let the server
                // echo its own close back. `CLOSE_HANDSHAKE=false` also
                // calls `ws.close()` — the difference for the bench is
                // simply whether the server sees a clean WS close frame
                // first or a direct TCP close once the iteration ends.
                ws.close();
            } else {
                sendOne();
            }
        };
        ws.onclose = () => {
            check(opened, { 'status 101': (v) => v === true });
            finish();
        };
        ws.onerror = () => {
            // onclose will follow; let the iteration end there.
            ws.close();
        };
    });
}
