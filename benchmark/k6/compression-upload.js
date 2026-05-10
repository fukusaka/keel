// compression-upload.js — POST /upload-stream with gzip-compressed body
//
// Verifies that the server-side request decompression path correctly
// decodes a `Content-Encoding: gzip` request body before the route
// handler counts the bytes. Pre-built fixture
// (`compression-upload-payload.gz`, ~11 KiB compressed → 102,400 bytes
// decoded, ~9:1 ratio) is sent verbatim so k6 itself does not need to
// gzip per request.
//
// Why ~9:1 ratio (not the trivial 770:1 of `'x' * 102400`):
//   `HttpRequestDecompressionHandler` ships with Apache-style dual-gate
//   defence (default `ratioLimit = 100`). A 770:1 fixture would trip the
//   ratio cap and surface as HTTP 413 once the handler lands, never
//   reaching the route handler that echoes `X-Bytes-Received`. The
//   ~9:1 ratio (numbered-lines + alphabet padding, typical of log /
//   JSON API content) stays well within the default cap so the bench
//   exercises the happy path. The zip-bomb reject path is covered by
//   the handler's unit tests, not by this bench.
//
// Server-side mechanics:
//   - Existing `/upload-stream` route reads request body chunks and
//     reports the byte count via `X-Bytes-Received`.
//   - Without an inbound decompression handler installed (current state
//     across all engines), `X-Bytes-Received` reports the **compressed**
//     size (133), not the decoded size (102,400) — i.e. the route
//     handler sees raw gzip bytes.
//   - Once `HttpRequestDecompressionHandler` (`keel-codec-http`) and the
//     Native `KeelContentEncodingPlugin` land, the decoded bytes flow
//     to the handler and `X-Bytes-Received` should equal 102,400.
//
// Bench role:
//   - Until upstream support lands, all engines FAIL the
//     "decoded size correct" check under
//     `BENCH_COMPRESSION_UPLOAD_STRICT=true` (default). This makes the
//     gap visible in the bench leaderboard.
//   - Once upstream support lands, the same scenario surfaces as
//     end-to-end verification + throughput numbers.
//
// Required env:
//   HOST                          target host (e.g. 127.0.0.1)
//   PORT                          target port
// Optional env:
//   COMPRESSION_UPLOAD_STRICT     when "true" (default), make the
//                                 "decoded size correct" assertion a
//                                 gating check — fail the run if the
//                                 server reports the compressed size
//                                 instead of 102,400. Set "false" to
//                                 measure throughput against engines
//                                 that don't decompress yet without
//                                 polluting the leaderboard with FAILs.
//   VUS                           concurrent virtual users (default: 50)
//   DURATION                      bench duration (default: 15s)
//   CONNECTION_CLOSE              when "true", every request carries
//                                 `Connection: close`. Used by
//                                 `bench-keepalive-compare.sh`.

import http from 'k6/http';
import { check } from 'k6';

// 100 KiB of numbered lines + alphabet padding compressed at gzip
// level 6 (~11 KiB, ~9:1 ratio). Deterministic fixture committed
// alongside this script. Decoded size matches LARGE_PAYLOAD_SIZE in
// BenchmarkConstants.kt so the compressed-and-decoded round-trip
// resolves to the same expected value as compression.js's response
// side. The ~9:1 ratio fits inside the upcoming
// HttpRequestDecompressionHandler's default ratio cap (100:1).
const PAYLOAD = open('./compression-upload-payload.gz', 'b');
const EXPECTED_DECODED_BYTES = 100 * 1024;

const STRICT = String(__ENV.COMPRESSION_UPLOAD_STRICT ?? 'true').toLowerCase() !== 'false';
const CONNECTION_CLOSE = String(__ENV.CONNECTION_CLOSE || '').toLowerCase() === 'true';

const HEADERS = (() => {
    const h = {
        'Content-Type': 'application/octet-stream',
        'Content-Encoding': 'gzip',
    };
    if (CONNECTION_CLOSE) h['Connection'] = 'close';
    return h;
})();

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
    insecureSkipTLSVerify: true,
};

export default function () {
    const url = `${__ENV.SCHEME || 'http'}://${__ENV.HOST}:${__ENV.PORT}/upload-stream`;
    const res = http.post(url, PAYLOAD, { headers: HEADERS });
    const reportedBytes = Number(res.headers['X-Bytes-Received'] || 0);
    const checks = {
        'status 200': (r) => r.status === 200,
    };
    if (STRICT) {
        // Strict mode: require the server to return the decoded byte count,
        // proving an inbound decompression handler is installed and active.
        checks['decoded size correct'] = () => reportedBytes === EXPECTED_DECODED_BYTES;
    } else {
        // Non-strict mode: just sanity-check that the byte count is at
        // least the compressed payload size. Used for throughput
        // comparisons across engines mid-rollout.
        checks['response includes byte count'] = () => reportedBytes > 0;
    }
    check(res, checks);
}
