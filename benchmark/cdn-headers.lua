-- wrk request-header set for the CDN-realistic 23-header workload.
--
-- Drives the server's header parse path with the same field set as the
-- HttpServerHotPathAllocationBenchmark "CDN 23 headers" scenario, so a
-- real-network run measures the per-request allocation / GC effect of
-- header storage on header-heavy edge/proxy traffic (not the minimal
-- single-Host /hello shape wrk sends by default).
--
-- Usage: wrk -s cdn-headers.lua http://host:port/hello

wrk.headers["Host"] = "api.example.com"
wrk.headers["User-Agent"] = "Mozilla/5.0 (Macintosh; Intel Mac OS X) AppleWebKit/605.1.15"
wrk.headers["Accept"] = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
wrk.headers["Accept-Language"] = "en-US,en;q=0.9"
wrk.headers["Accept-Encoding"] = "gzip, deflate, br"
wrk.headers["Connection"] = "keep-alive"
wrk.headers["Cookie"] = "session=abc123; tracking=xyz789; consent=accepted; ab_variant=B"
wrk.headers["Upgrade-Insecure-Requests"] = "1"
wrk.headers["Sec-Fetch-Dest"] = "document"
wrk.headers["Sec-Fetch-Mode"] = "navigate"
wrk.headers["CF-Connecting-IP"] = "203.0.113.42"
wrk.headers["CF-IPCountry"] = "US"
wrk.headers["CF-Ray"] = "abc123def456-DFW"
wrk.headers["CF-Visitor"] = '{"scheme":"https"}'
wrk.headers["X-Forwarded-For"] = "203.0.113.42, 172.16.0.1"
wrk.headers["X-Forwarded-Proto"] = "https"
wrk.headers["X-Real-IP"] = "203.0.113.42"
wrk.headers["traceparent"] = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
wrk.headers["tracestate"] = "rojo=00f067aa0ba902b7,congo=t61rcWkgMzE"
wrk.headers["X-Request-ID"] = "550e8400-e29b-41d4-a716-446655440000"
wrk.headers["Authorization"] = "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.sig"
wrk.headers["CDN-Loop"] = "cloudflare; subreqs=1"
