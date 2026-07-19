// Package clientbench holds the shared driving logic for the Go client
// benchmarks (net/http, fasthttp): CLI parsing, the concurrent load loop,
// HdrHistogram latency percentiles, and the harness result line. Each concrete
// client supplies only a `get(url) error` that performs one request and
// consumes its body.
//
// Latency percentiles use HdrHistogram with 3 significant figures — the same
// method the JVM (org.HdrHistogram) and Rust (hdrhistogram crate) drivers use,
// so all drivers report directly comparable p50/p99/p99.9. The number of
// significant figures determines the bucketing, so any HdrHistogram port with
// sigfigs=3 yields identical percentiles regardless of its tracked value range.
package clientbench

import (
	"fmt"
	"os"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/HdrHistogram/hdrhistogram-go"
)

// Latency histogram bounds shared by every driver: 1 ns to 60 s, 3 significant
// figures. The range only bounds allocation (no realistic request latency
// exceeds it); sigfigs=3 is what fixes the percentile values across drivers.
const (
	latLowest  = 1
	latHighest = 60_000_000_000
	latSigFigs = 3
)

func newLatencyHist() *hdrhistogram.Histogram {
	return hdrhistogram.New(latLowest, latHighest, latSigFigs)
}

// Config mirrors the JVM harness client flags.
type Config struct {
	Targets     []string // full URLs (base + endpoint)
	Endpoint    string
	Connections int
	Warmup      int
	Duration    int
	Pinned      bool
}

// Parse reads the --client-* flags shared with the JVM harness.
func Parse() Config {
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
		switch kv[0] {
		case "client-target":
			target = kv[1]
		case "client-endpoint":
			endpoint = kv[1]
		case "client-connections":
			connections, _ = strconv.Atoi(kv[1])
		case "client-warmup":
			warmup, _ = strconv.Atoi(kv[1])
		case "client-duration":
			duration, _ = strconv.Atoi(kv[1])
		case "client-target-mode":
			pinned = kv[1] == "pinned"
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
	if len(targets) == 0 {
		fmt.Fprintln(os.Stderr, "no target parsed from --client-target")
		os.Exit(1)
	}
	return Config{targets, endpoint, connections, warmup, duration, pinned}
}

// Run drives get across Connections goroutines for secs seconds and returns the
// throughput, an HdrHistogram of the per-request latencies (ns), and the error
// count.
func Run(cfg Config, secs int, get func(url string) error) (float64, *hdrhistogram.Histogram, int64) {
	deadline := time.Now().Add(time.Duration(secs) * time.Second)
	var completed, errors int64
	var pick uint64
	perWorker := make([]*hdrhistogram.Histogram, cfg.Connections)

	var wg sync.WaitGroup
	start := time.Now()
	for w := 0; w < cfg.Connections; w++ {
		wg.Add(1)
		go func(worker int) {
			defer wg.Done()
			local := newLatencyHist()
			var pinnedURL string
			if cfg.Pinned {
				pinnedURL = cfg.Targets[worker%len(cfg.Targets)]
			}
			for time.Now().Before(deadline) {
				url := pinnedURL
				if url == "" {
					i := int(atomic.AddUint64(&pick, 1)-1) % len(cfg.Targets)
					url = cfg.Targets[i]
				}
				t0 := time.Now()
				if err := get(url); err != nil {
					atomic.AddInt64(&errors, 1)
					continue
				}
				_ = local.RecordValue(time.Since(t0).Nanoseconds())
				atomic.AddInt64(&completed, 1)
			}
			perWorker[worker] = local
		}(w)
	}
	wg.Wait()
	elapsed := time.Since(start).Seconds()

	total := newLatencyHist()
	for _, l := range perWorker {
		if l != nil {
			total.Merge(l)
		}
	}
	rps := 0.0
	if elapsed > 0 {
		rps = float64(completed) / elapsed
	}
	return rps, total, errors
}

func pctMs(h *hdrhistogram.Histogram, q float64) float64 {
	return float64(h.ValueAtQuantile(q)) / 1e6
}

// Report prints the harness result line (bytes/op is n/a for native clients).
func Report(name string, cfg Config, rps float64, lat *hdrhistogram.Histogram, errors int64) {
	maxMs := float64(lat.Max()) / 1e6
	fmt.Printf("%s%s|%.0f|%.3f|%.3f|%.3f|%.3f|n/a|%d\n",
		name, cfg.Endpoint, rps,
		pctMs(lat, 50), pctMs(lat, 99), pctMs(lat, 99.9), maxMs, errors)
}

// LogStart writes the stderr banner shared by the Go client benches.
func LogStart(name string, cfg Config) {
	fmt.Fprintf(os.Stderr, "client bench: type=%s targets=%d conns=%d warmup=%ds duration=%ds\n",
		name, len(cfg.Targets), cfg.Connections, cfg.Warmup, cfg.Duration)
}
