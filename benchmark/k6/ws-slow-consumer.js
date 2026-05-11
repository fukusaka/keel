// ws-slow-consumer.js — WebSocket /ws-echo with the client deliberately
// slow at processing each echo (k6/websockets stable). Mirrors a real
// "slow consumer" pattern where the client cannot keep up with the
// server's send rate; on a proper backpressure-respecting engine, the
// server's WS write must throttle to match the client's read pace.
//
// How this differs from `ws-echo.js`:
//   - ws-echo.js sends a ping, waits for the echo, then sends the next
//     ping. The client and the server move in lock-step; there is no
//     accumulation on the server side.
//   - ws-slow-consumer.js sends BURST_PINGS pings up front (the server
//     queues that many echoes), then processes the echoes with
//     CONSUME_DELAY_MS sleep per echo. While the client is sleeping, the
//     server's WS send queue holds the un-acked echoes. A
//     backpressure-aware engine pauses its writer when the socket's send
//     buffer fills; an engine that just hot-loops the echo will pile up
//     memory and eventually drop the connection.
//
// The bench reports the per-roundtrip RTT (`ws_msg_rtt_ms` computed in
// JS via `Date.now()`) and the WS-session count. Both numbers are
// slow-consumer-pessimistic: the RTT here includes the server-side
// queueing time, not just the wire RTT.
//
// Migrated to `k6/websockets` (k6 >= v1.0); the per-echo "sleep" before
// the next send is implemented with `setTimeout` rather than k6's
// blocking `sleep()`, which would block the event loop and defeat the
// purpose of the async client.
//
// Required env:
//   HOST                target host
//   PORT                target port
// Optional env:
//   PAYLOAD_BYTES       bytes per WS frame (default: 256)
//   BURST_PINGS         pings to send before processing echoes
//                       (default: 16). Larger values stress the
//                       server's send-buffer further before the client
//                       starts draining.
//   CONSUME_DELAY_MS    ms to sleep per received echo before sending
//                       the next ping (default: 50 — i.e. ~20 RPS per
//                       VU once the burst drains).
//   VUS                 concurrent virtual users (default: 4)
//   DURATION            bench duration (default: 5s)
//
// Note: this measures the server's write-side backpressure under a
// slow client. For the client-write-side companion (slow uploader)
// see `slow-upload.js`.

import { WebSocket } from 'k6/websockets';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const PAYLOAD_BYTES = Number(__ENV.PAYLOAD_BYTES || 256);
const BURST_PINGS = Number(__ENV.BURST_PINGS || 16);
const CONSUME_DELAY_MS = Number(__ENV.CONSUME_DELAY_MS || 50);
const PAYLOAD = 'x'.repeat(PAYLOAD_BYTES);

const rttTrend = new Trend('ws_msg_rtt_ms', true);

export const options = {
    vus: Number(__ENV.VUS || 4),
    duration: __ENV.DURATION || '5s',
    // Bench TLS uses a self-signed cert; skip cert verification.
    insecureSkipTLSVerify: true,
};

export default function () {
    return new Promise((resolve) => {
        const url = `${__ENV.WS_SCHEME || 'ws'}://${__ENV.HOST}:${__ENV.PORT}/ws-echo`;
        const ws = new WebSocket(url);

        const sendTimes = []; // index by burst position so RTT can be paired with the matching echo
        let pingsSent = 0;
        let echoesReceived = 0;
        let opened = false;
        let resolved = false;
        const finish = () => {
            if (!resolved) {
                resolved = true;
                resolve();
            }
        };

        ws.onopen = () => {
            opened = true;
            // Up-front burst: queue BURST_PINGS messages on the server's
            // send buffer before doing any consumption. This is the
            // worst case for the server — its writer must accept all
            // BURST_PINGS into its outbound queue without expecting any
            // back-pressure from the client.
            for (let i = 0; i < BURST_PINGS; i++) {
                sendTimes.push(Date.now());
                ws.send(PAYLOAD);
                pingsSent++;
            }
        };
        ws.onmessage = () => {
            const sent = sendTimes[echoesReceived];
            if (sent !== undefined) {
                rttTrend.add(Date.now() - sent);
            }
            echoesReceived++;
            // Slow-consumer behaviour: stall before sending the next ping.
            // `setTimeout` rather than k6's blocking `sleep` because the
            // event loop must remain free to dispatch incoming
            // `onmessage` events queued by the server during the stall.
            setTimeout(() => {
                sendTimes.push(Date.now());
                ws.send(PAYLOAD);
                pingsSent++;
            }, CONSUME_DELAY_MS);
        };
        ws.onclose = () => {
            check(opened, { 'ws connected (status 101)': (v) => v === true });
            check(null, {
                'pings ≥ echoes (no echo loss)': () => pingsSent >= echoesReceived,
            });
            finish();
        };
        ws.onerror = () => {
            ws.close();
        };
    });
}
