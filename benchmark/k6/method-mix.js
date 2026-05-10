// method-mix.js — Cycle through HTTP methods on /method-echo to bench
// the engine's routing dispatch under a non-GET-heavy workload.
//
// Real services advertise PUT / DELETE / PATCH / OPTIONS / HEAD beyond
// the GET / POST that the rest of the bench exercises. The framework
// dispatchers (Ktor's `route { method() }` block, Spring's
// `RouterFunctions`, Vertx's `Router.route().method()`, Netty's manual
// switch) all have method-routing fast paths that GET-only bench
// cannot hit; this scenario surfaces the per-method dispatch cost.
//
// The server route is registered as "any method on /method-echo,
// reply 200 with `X-Echo-Method: <method>`" so k6 can validate the
// engine actually handed the request to the right handler.
//
// Required env:
//   HOST     target host
//   PORT     target port
// Optional env:
//   METHODS         comma-separated list of HTTP methods to rotate
//                   through (default: "GET,POST,PUT,DELETE,PATCH,OPTIONS").
//                   Each iteration picks the next method in the list,
//                   wrapping around.
//
//                   HEAD is intentionally excluded by default. The
//                   pipeline-http engines (direct keel pipeline, no
//                   framework auto-strip) do not strip the response body
//                   for HEAD, so they emit "ok" body bytes that k6's HTTP
//                   client then parses as the next response on the
//                   keep-alive connection ("Unsolicited response
//                   received on idle HTTP channel"). Framework engines
//                   (Ktor, Spring, Vertx, Netty raw) auto-strip and
//                   work fine with HEAD; pass METHODS=...,HEAD if you
//                   only want to bench framework engines.
//   VUS             concurrent virtual users (default: 50)
//   DURATION        bench duration (default: 15s)
//   SCHEME          http or https (default: http; set by
//                   bench-stream-one.sh from BENCH_SCHEME)

import http from 'k6/http';
import { check } from 'k6';

const METHODS = (__ENV.METHODS || 'GET,POST,PUT,DELETE,PATCH,OPTIONS')
    .split(',')
    .map((m) => m.trim().toUpperCase())
    .filter((m) => m.length > 0);

if (METHODS.length === 0) {
    throw new Error('METHODS env must contain at least one HTTP method');
}

export const options = {
    vus: Number(__ENV.VUS || 50),
    duration: __ENV.DURATION || '15s',
    // Bench TLS uses a self-signed cert; skip cert verification.
    insecureSkipTLSVerify: true,
};

let cursor = 0;

export default function () {
    const method = METHODS[cursor % METHODS.length];
    cursor++;
    const url = `${__ENV.SCHEME || 'http'}://${__ENV.HOST}:${__ENV.PORT}/method-echo`;
    const res = http.request(method, url, null);
    check(res, {
        'status 200': (r) => r.status === 200,
        // HEAD responses have no body; X-Echo-Method header carries the value.
        'echo method correct': (r) => (r.headers['X-Echo-Method'] || '').toUpperCase() === method,
    });
}
