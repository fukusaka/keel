// ws-echo.js — WebSocket echo throughput bench
//
// Each VU opens one WebSocket connection to /ws-echo and runs a ping-pong
// loop: send a fixed-size message, wait for the server to echo it back,
// send the next one. Measures end-to-end round-trip throughput (msgs/sec)
// and per-message latency (server uplink + echo + client downlink).
//
// k6 reports `ws_msgs_sent` / `ws_msgs_received` (msgs/sec) and
// `ws_session_duration` / `ws_msg_duration` (latency percentiles in
// `--summary-trend-stats`).
//
// Required env:
//   HOST       target host
//   PORT       target port
// Optional env:
//   PAYLOAD_BYTES message size in bytes (default: 256)
//   VUS           concurrent connections (default: 50)
//   DURATION      bench duration (default: 15s)
//   PING_PONGS    msgs per VU before close (default: unlimited until duration)

import ws from 'k6/ws';
import { check } from 'k6';

const PAYLOAD_BYTES = Number(__ENV.PAYLOAD_BYTES || 256);
const PAYLOAD = 'x'.repeat(PAYLOAD_BYTES);
const PING_PONGS = Number(__ENV.PING_PONGS || 0);

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
};

export default function () {
    const url = `ws://${__ENV.HOST}:${__ENV.PORT}/ws-echo`;
    const res = ws.connect(url, {}, function (socket) {
        let sent = 0;
        socket.on('open', () => socket.send(PAYLOAD));
        socket.on('message', (msg) => {
            check(msg, { 'echo size correct': (m) => m.length === PAYLOAD.length });
            sent += 1;
            if (PING_PONGS > 0 && sent >= PING_PONGS) {
                socket.close();
            } else {
                socket.send(PAYLOAD);
            }
        });
        socket.on('error', () => socket.close());
    });
    check(res, { 'status 101': (r) => r && r.status === 101 });
}
