// compression.js — GET /large with Accept-Encoding (gzip / deflate / br)
//
// Drives a configurable Accept-Encoding header against the existing
// `/large` endpoint (100 KB text payload). Validates that the server
// returned a compressed response by checking `Content-Encoding`, and
// that the post-decompress body still matches the expected payload
// size — k6's HTTP client transparently decodes gzip/deflate/br when
// the server emits the matching Content-Encoding, so `r.body` is
// already decompressed by the time the check runs.
//
// Engine support map (see BenchmarkConfig.compression KDoc):
//   - Ktor adapters (ktor-cio, ktor-netty, ktor-keel-*) — `install(Compression)`
//   - vertx — HttpServerOptions.setCompressionSupported(true)
//   - netty-raw — HttpContentCompressor in pipeline
//   - spring — server.compression.enabled=true property
//   - pipeline-http-* — not supported (codec compression is future work);
//     bench surfaces the gap as a missing Content-Encoding header → check fails.
//
// The point isn't to win at gzip CPU cost; it's to surface which engines
// actually serve compressed responses when asked, and to cross-compare
// throughput on the wire-side bytes a CDN / browser would observe.
//
// Required env:
//   HOST                 target host (e.g. 127.0.0.1)
//   PORT                 target port
// Optional env:
//   COMPRESSION_TYPE     Accept-Encoding value (default: "gzip"). Values
//                        passed verbatim — "gzip", "br", "deflate",
//                        "gzip, deflate", "identity" all work. Use
//                        "identity" to send no Accept-Encoding fallback
//                        (server should return uncompressed).
//   COMPRESSION_STRICT   when "true", make the `content-encoding present`
//                        assertion a gating check — fail the run if the
//                        server returned uncompressed despite the client
//                        asking. Default "false" so the same scenario
//                        also measures throughput on engines that don't
//                        support compression yet (pipeline-http-* +
//                        Native ktor-keel-*) — the bench then surfaces
//                        the comparison "compression-on engines vs
//                        compression-missing engines under the same
//                        request shape" as a single leaderboard table.
//                        Set to "true" for verification runs that want
//                        to assert the engine actually emits a compressed
//                        wire payload.
//   VUS                  concurrent virtual users (default: 50)
//   DURATION             bench duration (default: 15s)
//   CONNECTION_CLOSE     when "true", every request carries
//                        `Connection: close` so the TCP connection tears
//                        down after each round-trip. Same plumbing as
//                        upload.js — used by `bench-keepalive-compare.sh`.

import http from 'k6/http';
import { check } from 'k6';

const COMPRESSION_TYPE = String(__ENV.COMPRESSION_TYPE || 'gzip');
const COMPRESSION_STRICT = String(__ENV.COMPRESSION_STRICT || '').toLowerCase() === 'true';
const CONNECTION_CLOSE = String(__ENV.CONNECTION_CLOSE || '').toLowerCase() === 'true';

// k6 auto-decodes gzip / deflate / br when the server emits the matching
// Content-Encoding, so the body length check below operates on the
// decompressed bytes (matches the historical /large bench's 100 KB).
const HEADERS = (() => {
    const h = { 'Accept-Encoding': COMPRESSION_TYPE };
    if (CONNECTION_CLOSE) h['Connection'] = 'close';
    return h;
})();

// Expected decompressed payload size for /large (matches LARGE_PAYLOAD_SIZE
// in BenchmarkConstants.kt). Hard-coded here rather than imported because
// k6 scripts can't easily share constants with Kotlin sources — if the
// constant changes, this script needs updating in lockstep.
const LARGE_PAYLOAD_BYTES = 100 * 1024;

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
    insecureSkipTLSVerify: true,
};

export default function () {
    const url = `${__ENV.SCHEME || 'http'}://${__ENV.HOST}:${__ENV.PORT}/large`;
    const res = http.get(url, { headers: HEADERS });
    // Always assert correctness invariants. These two are universal: the
    // server returned 200 and the body (after k6's transparent gzip /
    // deflate / br decode) is the historical /large payload size.
    const checks = {
        'status 200': (r) => r.status === 200,
        'decompressed body size correct': (r) => r.body && r.body.length === LARGE_PAYLOAD_BYTES,
    };
    // Optionally assert the wire was compressed. Off by default so the
    // same scenario measures throughput on every engine (including
    // pipeline-http-* and Native ktor-keel-* where codec / plugin
    // support is missing) — the bench then reports a single leaderboard
    // table comparing compression-enabled engines against
    // compression-missing engines under the same request shape, instead
    // of refusing to score the latter.
    if (COMPRESSION_STRICT && COMPRESSION_TYPE.toLowerCase() !== 'identity') {
        checks['content-encoding present'] = (r) => Boolean(r.headers['Content-Encoding']);
    }
    check(res, checks);
}
