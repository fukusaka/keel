// Go net/http client benchmark — a native reference for the keel HTTP client
// bench. Go's stdlib net/http reuses keep-alive connections (once the body is
// drained) and is one of the most widely used HTTP clients, so it is a native
// ceiling counterpart to the JVM reference clients. Accepts the same CLI flags
// as the JVM harness and prints the same result line, so bench-client.sh can
// drive it like any other client type.
//
// Line format: <name><endpoint>|<rps>|<p50ms>|<p99ms>|<p99.9ms>|<maxms>|<b/op>|<errors>
// bytes/op is n/a for native clients. Percentiles are exact (sorted samples).
package main

import (
	"fmt"
	"io"
	"net/http"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

const name = "go-nethttp"

type config struct {
	targets     []string // full URLs (base + endpoint)
	endpoint    string
	connections int
	warmup      int
	duration    int
	pinned      bool
}

func parse() config {
	target := ""
	endpoint := "/hello"
	connections := 50
	warmup := 3
	duration := 10
	pinned := false
	for _, arg := range os.Args[1:] {
		if !strings.HasPrefix(arg, "--") || !strings.Contains(arg, "=") {
			continue
		}
		kv := strings.SplitN(strings.TrimPrefix(arg, "--"), "=", 2)
		k, v := kv[0], kv[1]
		switch k {
		case "client-target":
			target = v
		case "client-endpoint":
			endpoint = v
		case "client-connections":
			connections, _ = strconv.Atoi(v)
		case "client-warmup":
			warmup, _ = strconv.Atoi(v)
		case "client-duration":
			duration, _ = strconv.Atoi(v)
		case "client-target-mode":
			pinned = v == "pinned"
		}
	}
	if target == "" {
		fmt.Fprintln(os.Stderr, "missing --client-target")
		os.Exit(1)
	}
	var targets []string
	for _, t := range strings.Split(target, ",") {
		t = strings.TrimSpace(t)
		if t == "" {
			continue
		}
		targets = append(targets, strings.TrimRight(t, "/")+endpoint)
	}
	return config{targets, endpoint, connections, warmup, duration, pinned}
}

type phase struct {
	rps     float64
	latency []int64 // sorted nanos
	errors  int64
}

func runPhase(client *http.Client, cfg config, secs int) phase {
	deadline := time.Now().Add(time.Duration(secs) * time.Second)
	var completed, errors int64
	var pick uint64
	perWorker := make([][]int64, cfg.connections)

	var wg sync.WaitGroup
	start := time.Now()
	for w := 0; w < cfg.connections; w++ {
		wg.Add(1)
		go func(worker int) {
			defer wg.Done()
			local := make([]int64, 0, 1024)
			var pinnedURL string
			if cfg.pinned {
				pinnedURL = cfg.targets[worker%len(cfg.targets)]
			}
			for time.Now().Before(deadline) {
				url := pinnedURL
				if url == "" {
					i := int(atomic.AddUint64(&pick, 1)-1) % len(cfg.targets)
					url = cfg.targets[i]
				}
				t0 := time.Now()
				resp, err := client.Get(url)
				if err != nil {
					atomic.AddInt64(&errors, 1)
					continue
				}
				// Drain + close so the connection returns to the keep-alive pool
				// (Go reuses a connection only if its body is fully read).
				_, _ = io.Copy(io.Discard, resp.Body)
				resp.Body.Close()
				local = append(local, time.Since(t0).Nanoseconds())
				atomic.AddInt64(&completed, 1)
			}
			perWorker[worker] = local
		}(w)
	}
	wg.Wait()
	elapsed := time.Since(start).Seconds()

	var all []int64
	for _, l := range perWorker {
		all = append(all, l...)
	}
	sort.Slice(all, func(i, j int) bool { return all[i] < all[j] })
	rps := 0.0
	if elapsed > 0 {
		rps = float64(completed) / elapsed
	}
	return phase{rps, all, errors}
}

func pctMs(sorted []int64, q float64) float64 {
	if len(sorted) == 0 {
		return 0
	}
	idx := int(q * float64(len(sorted)-1))
	return float64(sorted[idx]) / 1e6
}

func main() {
	cfg := parse()
	transport := &http.Transport{
		MaxIdleConns:        cfg.connections,
		MaxIdleConnsPerHost: cfg.connections, // default is 2 -> would churn under load
		MaxConnsPerHost:     cfg.connections,
		IdleConnTimeout:     30 * time.Second,
	}
	client := &http.Client{Transport: transport}
	fmt.Fprintf(os.Stderr, "client bench: type=%s targets=%d conns=%d warmup=%ds duration=%ds\n",
		name, len(cfg.targets), cfg.connections, cfg.warmup, cfg.duration)
	if cfg.warmup > 0 {
		runPhase(client, cfg, cfg.warmup)
	}
	p := runPhase(client, cfg, cfg.duration)
	maxMs := 0.0
	if len(p.latency) > 0 {
		maxMs = float64(p.latency[len(p.latency)-1]) / 1e6
	}
	fmt.Printf("%s%s|%.0f|%.3f|%.3f|%.3f|%.3f|n/a|%d\n",
		name, cfg.endpoint, p.rps,
		pctMs(p.latency, 0.50), pctMs(p.latency, 0.99), pctMs(p.latency, 0.999), maxMs, p.errors)
}
