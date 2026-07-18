// Go net/http client benchmark — a native reference for the keel HTTP client
// bench. net/http reuses keep-alive connections once the body is drained, and
// is one of the most widely used HTTP clients. Accepts the same CLI flags as
// the JVM harness and prints the same result line (name=go-nethttp).
package main

import (
	"io"
	"net/http"
	"time"

	"go-bench/internal/clientbench"
)

func main() {
	cfg := clientbench.Parse()
	// MaxIdleConnsPerHost default is 2 -> would churn under load; size it to the
	// connection count so N keep-alive sockets are held and reused.
	transport := &http.Transport{
		MaxIdleConns:        cfg.Connections,
		MaxIdleConnsPerHost: cfg.Connections,
		MaxConnsPerHost:     cfg.Connections,
		IdleConnTimeout:     30 * time.Second,
	}
	client := &http.Client{Transport: transport}
	get := func(url string) error {
		resp, err := client.Get(url)
		if err != nil {
			return err
		}
		// Drain + close so the connection returns to the keep-alive pool.
		_, _ = io.Copy(io.Discard, resp.Body)
		return resp.Body.Close()
	}

	clientbench.LogStart("go-nethttp", cfg)
	if cfg.Warmup > 0 {
		clientbench.Run(cfg, cfg.Warmup, get)
	}
	rps, lat, errs := clientbench.Run(cfg, cfg.Duration, get)
	clientbench.Report("go-nethttp", cfg, rps, lat, errs)
}
