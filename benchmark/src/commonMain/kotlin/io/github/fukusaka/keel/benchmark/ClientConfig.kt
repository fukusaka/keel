package io.github.fukusaka.keel.benchmark

/**
 * Settings for the client benchmark role (`--role=client`).
 *
 * The process under test is an HTTP *client* driving a fixture server; this is
 * the inverse of the default server role where an external load generator
 * (wrk / k6) drives a keel server. Methodology follows the cross-OSS survey
 * (Ktor client-benchmarks, undici, OkHttp MockWebServer, h2load, wrk2): an
 * in-process loopback fixture with a trivial endpoint so the *client* is the
 * component measured, reference-client A/B, HdrHistogram latency with
 * coordinated-omission correction, and allocations-per-request as a
 * first-class metric.
 *
 * @property clientType which client drives the load: `keel` (the keel client,
 *   pending its standalone implementation — currently the codec-on-connect
 *   cold path), `java` (JDK `java.net.http.HttpClient`), or `ktor-cio`
 *   (Ktor `HttpClient(CIO)`). Reference clients establish the A/B ceiling.
 * @property endpoint fixture path to request (`/hello` = 13 B, `/large` =
 *   100 KB) — small isolates per-request framing/alloc, large isolates
 *   throughput / body copy.
 * @property connections concurrent connections / pool size (undici default 50).
 * @property durationSec measurement window seconds (used when [requests] == 0).
 * @property warmupSec warm-up seconds before measurement (JIT + pool warm),
 *   discarded from results.
 * @property requests fixed request count; when > 0 it overrides [durationSec]
 *   (ab / h2load `-n` style).
 * @property mode load model. `closed` (fixed connections looping as fast as
 *   possible) measures max throughput; `open` (constant arrival rate, see
 *   [rateRps]) measures latency without coordinated omission (wrk2 model).
 *   Latency claims must come from `open`.
 * @property rateRps target requests/sec for `open` mode (0 = unset).
 * @property targetUrl **required** fixture URL, pointing at a SEPARATE fixture
 *   process (e.g. rust-bench on loopback). The harness only connects; it never
 *   starts a fixture in-process (that would share the client JVM and
 *   contaminate the numbers — `bench-client.sh` manages the fixture lifecycle).
 */
data class ClientConfig(
    val clientType: String = "keel",
    val endpoint: String = "/hello",
    val connections: Int = 50,
    val durationSec: Int = 10,
    val warmupSec: Int = 3,
    val requests: Int = 0,
    val mode: String = "closed",
    val rateRps: Int = 0,
    val targetUrl: String? = null,
)
