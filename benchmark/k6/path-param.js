// path-param.js — GET /items/{id} with rotating ids to bench the
// engine's path-parameter routing dispatch.
//
// Real REST services route on path templates (`/users/123`,
// `/orders/abc-456`, `/api/v1/items/789`). Each framework dispatcher
// has a different cost: Ktor compiles routing trees lazily, Spring's
// `RouterFunctions` walks predicates per request, Vertx's `Router`
// does regex matching, Netty raw has manual string parsing,
// pipeline-http has a single switch on the path prefix. The cost
// difference shows up only when the path is parameterised; the
// existing fixed-path benches (/hello / /large) miss it entirely.
//
// The server route is registered as `GET /items/{id}` and replies
// 200 with `X-Item-Id: <id>` so k6 can confirm dispatch worked. The
// id is a small integer so the bench is not bottlenecked by the
// payload size.
//
// Required env:
//   HOST     target host
//   PORT     target port
// Optional env:
//   ID_RANGE        modulus for the iteration counter; the path id is
//                   `(VU * 1000 + iter) % ID_RANGE` so different VUs
//                   exercise different ids in parallel (default: 100).
//                   Set to 1 to reuse the same id every iteration
//                   (caches the parsed route in some engines).
//   VUS             concurrent virtual users (default: 50)
//   DURATION        bench duration (default: 15s)
//   SCHEME          http or https (default: http; set by
//                   bench-stream-one.sh from BENCH_SCHEME)

import http from 'k6/http';
import { check } from 'k6';
import { vu } from 'k6/execution';

const ID_RANGE = Number(__ENV.ID_RANGE || 100);

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
    // Bench TLS uses a self-signed cert; skip cert verification.
    insecureSkipTLSVerify: true,
};

let iterCounter = 0;

export default function () {
    // Compute id locally per VU. k6 v1 exposes vu.idInTest for the VU
    // index; we combine it with a per-iteration counter so concurrent
    // VUs hit distinct paths, and so the same VU rotates through ids
    // over its iterations.
    const id = (vu.idInTest * 1000 + iterCounter++) % ID_RANGE;
    const url = `${__ENV.SCHEME || 'http'}://${__ENV.HOST}:${__ENV.PORT}/items/${id}`;
    const res = http.get(url);
    check(res, {
        'status 200': (r) => r.status === 200,
        'item id echoed': (r) => Number(r.headers['X-Item-Id'] || -1) === id,
    });
}
