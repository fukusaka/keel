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
// **Defensive exit on abnormal server close (2026-05-11)**:
//
// Some servers reject frames whose payload exceeds their read buffer
// with an immediate 1003 (`unsupported data`) close frame (e.g.
// zig-bench's `std.http.Server.WebSocket.readSmallMessage` — see
// status.md "ws-large hang の真因" note). To keep VU iterations
// forward-progressing in normal cases (e.g. servers that close
// gracefully between iterations) this script:
//
//   1. Tracks a `closed` flag via `socket.on('close')` so subsequent
//      `sendOne()` calls become no-ops after the server has closed.
//   2. Arms a per-iteration watchdog `socket.setTimeout` that force-
//      calls `socket.close()` if no `binaryMessage` event arrives
//      within `WS_LARGE_WATCHDOG_MS` (default 30000 ms = 30 s).
//   3. Re-arms the watchdog on every successful echo so steady-state
//      workloads (1 MB at 350 msgs/sec against a healthy server) are
//      not interrupted.
//
// **Limitation (verified 2026-05-11 against zig-bench)**: the JS
// watchdog **cannot** defeat the case where k6 v1.7 `k6/ws`
// `socket.sendBinary(1 MB)` blocks the JS event loop synchronously
// because the underlying Go `net.Conn.Write` is stuck on a closed-
// then-RST TCP socket. The `socket.setTimeout` callback is queued
// on the same JS event loop and never gets a chance to fire while
// the write is in progress. A/B observed: against ktor-cio JVM
// (known-good) `terminated signal` is honoured within ~30 s; against
// zig-bench (MessageOversize close) the iteration hangs for 5+
// minutes regardless of watchdog or `timeout(1)`. The mitigation in
// `bench-stream-one.sh` (skip Phase 2 Native ref for `ws-large`) is
// therefore required to keep bench pipelines forward-progressing.
// The JS-side defensive code in this file is still useful for
// graceful-close cases and as defence-in-depth.
//
// Required env:
//   HOST       target host
//   PORT       target port
// Optional env:
//   WS_LARGE_BYTES       single-message size in bytes (default: 1048576 = 1 MB)
//   VUS                  concurrent connections (default: 4)
//   DURATION             bench duration (default: 15s)
//   WS_LARGE_WATCHDOG_MS per-iteration watchdog (default: 30000)

import ws from 'k6/ws';
import { check } from 'k6';
import { Trend } from 'k6/metrics';

const WS_LARGE_BYTES = Number(__ENV.WS_LARGE_BYTES || 1048576);
const WS_LARGE_WATCHDOG_MS = Number(__ENV.WS_LARGE_WATCHDOG_MS || 30000);

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
        let closed = false;
        let sendTs = 0;
        let watchdogId = null;

        // Arm / re-arm the per-iteration watchdog. Called once on `open`
        // and again on every successful echo. The watchdog force-closes
        // the socket if the next echo doesn't arrive within
        // WS_LARGE_WATCHDOG_MS — guards against abnormal server close
        // patterns (e.g. Phase 2 Native ref MessageOversize fast-fail)
        // that put k6/ws into a SIGTERM-ignoring state on partial
        // send.
        const armWatchdog = () => {
            watchdogId = socket.setTimeout(() => {
                if (!closed) {
                    closed = true;
                    socket.close();
                }
            }, WS_LARGE_WATCHDOG_MS);
        };

        const sendOne = () => {
            if (closed) return;
            sendTs = Date.now();
            socket.sendBinary(PAYLOAD);
        };

        socket.on('open', () => {
            armWatchdog();
            sendOne();
        });
        socket.on('binaryMessage', (msg) => {
            if (closed) return;
            rttMs.add(Date.now() - sendTs);
            const len = msg.byteLength || 0;
            check(len, { 'echo size correct': (n) => n === expectedLen });
            // Echo received — re-arm the watchdog for the next round
            // trip and continue. `socket.setTimeout` returns a new id
            // each call, so the previous timer becomes a no-op once
            // its callback runs (we don't need to clearTimeout).
            armWatchdog();
            sendOne();
        });
        socket.on('close', () => {
            closed = true;
        });
        socket.on('error', () => {
            closed = true;
            socket.close();
        });
    });
    check(res, { 'status 101': (r) => r && r.status === 101 });
}
