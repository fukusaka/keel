// multipart.js — POST /multipart-upload throughput bench (multipart/form-data
// request body with N parts of K bytes each).
//
// Drives a configurable number of virtual users that POST a hand-built
// multipart/form-data payload to /multipart-upload. The server parses the
// parts (framework engines) or discards the body chunk-by-chunk
// (pipeline-mode engines without a framework multipart parser; those are
// out of scope for this scenario today). RPS reflects the request-body
// parse throughput more than the response write path (the response is a
// short JSON ack).
//
// Why this exists in addition to upload-stream:
//   - upload-stream measures raw byte drain. multipart-upload measures the
//     same wire bandwidth plus the framework's multipart-parser cost.
//   - File-upload-from-browser is the canonical real-world POST pattern;
//     keep-alive vs Connection: close, payload size, and parser library
//     choice all affect the headline number engines need to advertise.
//
// Required env:
//   HOST            target host
//   PORT            target port
// Optional env:
//   PARTS           number of parts in each request (default: 5)
//   PART_BYTES      bytes per part body (default: 4096)
//   VUS             concurrent virtual users (default: 50)
//   DURATION        bench duration (default: 15s)
//   CONNECTION_CLOSE  when "true", carry `Connection: close` per request
//                     (forwarded from BENCH_HTTP_CONNECTION_CLOSE; same
//                     contract as upload.js / sse.js). Default off.
//
// Wire format: a single multipart/form-data body with PARTS parts. Each
// part has Content-Disposition: form-data; name="part<i>"; filename=...
// + a fixed-size body. The boundary is a constant the server can parse
// once at module load.

import http from 'k6/http';
import { check } from 'k6';

const PARTS = Number(__ENV.PARTS || 5);
const PART_BYTES = Number(__ENV.PART_BYTES || 4096);
const BOUNDARY = 'KeelBenchBoundaryV1';

const CONNECTION_CLOSE = String(__ENV.CONNECTION_CLOSE || '').toLowerCase() === 'true';
const HEADERS = CONNECTION_CLOSE
    ? { 'Content-Type': `multipart/form-data; boundary=${BOUNDARY}`, 'Connection': 'close' }
    : { 'Content-Type': `multipart/form-data; boundary=${BOUNDARY}` };

// Build the multipart body once at module load — k6 shares it across all
// VU iterations so memory cost is one copy total, regardless of VUs.
function buildBody(parts, partBytes) {
    const partPayload = 'x'.repeat(partBytes);
    const lines = [];
    for (let i = 0; i < parts; i++) {
        lines.push(`--${BOUNDARY}`);
        lines.push(`Content-Disposition: form-data; name="part${i}"; filename="part${i}.bin"`);
        lines.push('Content-Type: application/octet-stream');
        lines.push('');
        lines.push(partPayload);
    }
    lines.push(`--${BOUNDARY}--`);
    lines.push('');
    return lines.join('\r\n');
}

const BODY = buildBody(PARTS, PART_BYTES);

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
};

export default function () {
    const url = `http://${__ENV.HOST}:${__ENV.PORT}/multipart-upload`;
    const res = http.post(url, BODY, { headers: HEADERS });
    check(res, {
        'status 200': (r) => r.status === 200,
        'parts received correct': (r) => Number(r.headers['X-Parts-Received'] || 0) === PARTS,
    });
}
