// Go fasthttp client benchmark — a faster alternative Go HTTP client than the
// stdlib net/http (valyala/fasthttp), for the stdlib-vs-fasthttp comparison
// within Go. HTTP/1.1 only, with an internal keep-alive connection pool.
// Accepts the same CLI flags as the JVM harness and prints the same result line
// (name=go-fasthttp).
package main

import (
	"github.com/valyala/fasthttp"

	"go-bench/internal/clientbench"
)

func main() {
	cfg := clientbench.Parse()
	client := &fasthttp.Client{
		MaxConnsPerHost:     cfg.Connections,
		MaxIdleConnDuration: 30_000_000_000, // 30s in ns
	}
	get := func(url string) error {
		req := fasthttp.AcquireRequest()
		resp := fasthttp.AcquireResponse()
		defer fasthttp.ReleaseRequest(req)
		defer fasthttp.ReleaseResponse(resp)
		req.SetRequestURI(url)
		if err := client.Do(req, resp); err != nil {
			return err
		}
		_ = resp.Body() // consume so nothing is dead-code-eliminated
		return nil
	}

	clientbench.LogStart("go-fasthttp", cfg)
	if cfg.Warmup > 0 {
		clientbench.Run(cfg, cfg.Warmup, get)
	}
	rps, lat, errs := clientbench.Run(cfg, cfg.Duration, get)
	clientbench.Report("go-fasthttp", cfg, rps, lat, errs)
}
