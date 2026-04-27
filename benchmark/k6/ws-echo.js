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
//   VUS           concurrent connections (default: 50)
//   DURATION      bench duration (default: 15s)
//   PING_PONGS    msgs per VU before close (default: unlimited until duration)
//   PING_INTERVAL echoes between control-frame pings (default: 32)

import ws from 'k6/ws';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const PAYLOAD_BYTES = Number(__ENV.PAYLOAD_BYTES || 256);
const PAYLOAD = 'x'.repeat(PAYLOAD_BYTES);
const PING_PONGS = Number(__ENV.PING_PONGS || 0);
const PING_INTERVAL = Number(__ENV.PING_INTERVAL || 32);

// JS-side data-frame RTT (ms precision). Useful as a sanity check
// against `ws_ping`, which is Go-side ns precision but measures
// control frames rather than echo data frames.
const rttMs = new Trend('ws_msg_rtt_ms', true);

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
};

export default function () {
    const url = `ws://${__ENV.HOST}:${__ENV.PORT}/ws-echo`;
    const res = ws.connect(url, {}, function (socket) {
        let sent = 0;
        let sendTs = 0;
        const sendOne = () => {
            sendTs = Date.now();
            socket.send(PAYLOAD);
        };
        socket.on('open', sendOne);
        socket.on('message', (msg) => {
            rttMs.add(Date.now() - sendTs);
            check(msg, { 'echo size correct': (m) => m.length === PAYLOAD.length });
            sent += 1;
            // Interleave a control-frame ping so the Go-side ws_ping
            // Trend captures sub-millisecond RTT samples without a
            // separate scenario.
            if (PING_INTERVAL > 0 && sent % PING_INTERVAL === 0) {
                socket.ping();
            }
            if (PING_PONGS > 0 && sent >= PING_PONGS) {
                socket.close();
            } else {
                sendOne();
            }
        });
        socket.on('error', () => socket.close());
    });
    check(res, { 'status 101': (r) => r && r.status === 101 });
}
