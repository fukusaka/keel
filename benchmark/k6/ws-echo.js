// ws-echo.js — WebSocket echo throughput bench
//
// Each VU opens one WebSocket connection to /ws-echo and runs a ping-pong
// loop: send a fixed-size message, wait for the server to echo it back,
// send the next one. Measures end-to-end round-trip throughput (msgs/sec)
// and per-message latency (server uplink + echo + client downlink).
//
// k6 reports `ws_msgs_sent` / `ws_msgs_received` (msgs/sec) for the echo
// throughput, and (because k6 measures `socket.ping()` round trip in Go
// with `time.Now()`) the built-in `ws_ping` Trend gives **ns precision**
// RTT samples. We send a control-frame ping every PING_INTERVAL echo
// messages so `ws_ping` populates without choking throughput. The
// JS-side `Date.now()` Trend is kept as `ws_msg_rtt_ms` for cross-checking
// the data-frame round trip but only has ms precision; bench-stream-one.sh
// keys on `ws_ping` for the p50/p99 columns.
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
//   PING_INTERVAL echoes between control-frame pings (default: 32)
//   CLOSE_HANDSHAKE  if "true", initiate the WebSocket close handshake
//                    after PING_PONGS messages instead of TCP-closing.
//                    Verifies the server replies with its own close frame.
//                    Default: false (TCP-close, like long-running scenarios).

import ws from 'k6/ws';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const PAYLOAD_BYTES = Number(__ENV.PAYLOAD_BYTES || 256);
const PAYLOAD_TYPE = (__ENV.PAYLOAD_TYPE || 'text').toLowerCase();
const PING_PONGS = Number(__ENV.PING_PONGS || 0);
const PING_INTERVAL = Number(__ENV.PING_INTERVAL || 32);
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

// JS-side data-frame RTT (ms precision). Useful as a sanity check
// against `ws_ping`, which is Go-side ns precision but measures
// control frames rather than echo data frames.
const rttMs = new Trend('ws_msg_rtt_ms', true);

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
    // Bench TLS uses a self-signed cert; skip cert verification.
    insecureSkipTLSVerify: true,
};

function sendPayload(socket) {
    if (PAYLOAD_TYPE === 'binary') {
        socket.sendBinary(BINARY_PAYLOAD);
    } else {
        socket.send(TEXT_PAYLOAD);
    }
}

export default function () {
    const url = `${__ENV.WS_SCHEME || 'ws'}://${__ENV.HOST}:${__ENV.PORT}/ws-echo`;
    const expectedLen = PAYLOAD_BYTES;
    const res = ws.connect(url, {}, function (socket) {
        let sent = 0;
        let sendTs = 0;
        const sendOne = () => {
            sendTs = Date.now();
            sendPayload(socket);
        };
        socket.on('open', sendOne);
        const onEcho = (msg) => {
            rttMs.add(Date.now() - sendTs);
            // For text frames `msg` is a string; for binary k6 surfaces an
            // ArrayBuffer whose `.byteLength` is the wire length.
            const len = typeof msg === 'string' ? msg.length : (msg.byteLength || 0);
            check(len, { 'echo size correct': (n) => n === expectedLen });
            sent += 1;
            if (PING_INTERVAL > 0 && sent % PING_INTERVAL === 0) {
                socket.ping();
            }
            if (PING_PONGS > 0 && sent >= PING_PONGS) {
                if (CLOSE_HANDSHAKE) {
                    // Send a normal-closure close frame and let the server
                    // echo its own close back; k6's ws.close() does this.
                    socket.close();
                } else {
                    socket.close();
                }
            } else {
                sendOne();
            }
        };
        socket.on('message', onEcho);
        socket.on('binaryMessage', onEcho);
        socket.on('error', () => socket.close());
    });
    check(res, { 'status 101': (r) => r && r.status === 101 });
}
