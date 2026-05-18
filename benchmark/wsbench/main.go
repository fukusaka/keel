// wsbench — WebSocket benchmark client with frame-level control.
//
// k6's `k6/ws` module hides WebSocket frame internals (fin, opcode,
// continuation) and the JS API can only send a complete message at a
// time. To bench fragment workloads (RFC 6455 §5.4) the client has to
// construct fragmented frames explicitly, which requires either an xk6
// build (custom k6 binary) or a separate client. We picked "separate
// client" so the bench harness keeps the stock `k6` binary on every
// host.
//
// The client mimics bench-stream-one.sh's k6-driven contract:
//   - Concurrent VUs
//   - Wall-clock duration limit
//   - Output line `<name>|<msgs/sec>|<p50>|<p99>` parsable by
//     bench-stream-one.sh's existing ws-fragment scenario plumbing.
//
// Scenarios:
//   - fragment-recv: client sends a single message split into N
//     fragmented frames (text fin=false → continuation fin=false × N
//     → continuation fin=true), reads one complete echo back. Tests
//     the server's RECEIVE-side fragment reassembly.
//   - fragment-send: client sends a single complete message, server is
//     expected to echo it back FRAGMENTED. Requires the server to have
//     a `/ws-echo-fragment` route that splits its echo. Tests the
//     server's SEND-side fragment emission. (Bench is over the same
//     /ws-echo route by default — server picks fragmented send if it
//     opts in.)
//   - deflate: each VU opens a permessage-deflate (RFC 7692) connection
//     and echoes a synthetic compressible text payload. gorilla
//     negotiates the extension via Dialer.EnableCompression and
//     auto-compresses sent / auto-decompresses received messages. The
//     `-compression` flag turns the negotiation off so the same server
//     endpoint can be A/B-tested compressed vs uncompressed. permessage-
//     deflate only wins under a bandwidth cap (see bench-remote-ws.sh) —
//     on an unconstrained link compression is pure CPU cost. Default
//     path is /ws-deflate.
//
// Usage:
//
//	wsbench -name=<engine> -scenario=<fragment-recv|fragment-send|deflate> \
//	    -host=<host> -port=<port> [-vus=N] [-duration=15s] \
//	    [-bytes=4096] [-fragments=4] [-path=/ws-echo] [-compression=true]
package main

import (
	"context"
	"crypto/tls"
	"flag"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/gorilla/websocket"
)

func main() {
	var (
		name        = flag.String("name", "wsbench", "engine name for the output row")
		scenario    = flag.String("scenario", "fragment-recv", "fragment-recv | fragment-send | deflate")
		host        = flag.String("host", "127.0.0.1", "WebSocket host")
		port        = flag.Int("port", 18090, "WebSocket port")
		path        = flag.String("path", "", "WebSocket path (default: /ws-echo, or /ws-deflate for the deflate scenario)")
		vus         = flag.Int("vus", 16, "concurrent virtual users (sessions)")
		duration    = flag.Duration("duration", 15*time.Second, "bench wall-clock duration")
		bytes       = flag.Int("bytes", 4096, "single-message size in bytes (split across fragments for fragment-recv)")
		fragments   = flag.Int("fragments", 4, "number of frames the message is split into (fragment-recv)")
		scheme      = flag.String("scheme", "ws", "WebSocket URL scheme: 'ws' or 'wss'. When 'wss', the dialer skips TLS cert verification (bench cert is self-signed).")
		compression = flag.Bool("compression", true, "deflate scenario: negotiate permessage-deflate (RFC 7692). Set false for the uncompressed A/B leg.")
	)
	flag.Parse()

	if *fragments < 1 {
		fmt.Fprintln(os.Stderr, "fragments must be >= 1")
		os.Exit(1)
	}
	if *bytes < *fragments {
		fmt.Fprintln(os.Stderr, "bytes must be >= fragments (one byte per fragment minimum)")
		os.Exit(1)
	}

	switch *scheme {
	case "ws", "wss":
	default:
		fmt.Fprintf(os.Stderr, "unknown scheme %q (expected ws|wss)\n", *scheme)
		os.Exit(1)
	}
	// Resolve the default path per scenario: the deflate scenario targets
	// the /ws-deflate route (which negotiates permessage-deflate), every
	// other scenario uses the plain /ws-echo route.
	resolvedPath := *path
	if resolvedPath == "" {
		if *scenario == "deflate" {
			resolvedPath = "/ws-deflate"
		} else {
			resolvedPath = "/ws-echo"
		}
	}
	u := url.URL{Scheme: *scheme, Host: fmt.Sprintf("%s:%d", *host, *port), Path: resolvedPath}

	// TCP_NODELAY is required for fragment-recv correctness: wsbench writes
	// each fragment as a separate Write() to the underlying TCP connection.
	// Without TCP_NODELAY, Linux's Nagle algorithm delays frames 2..N until
	// the previous frame is ACK'd (~40 ms delayed-ACK timer), collapsing
	// throughput to ~2 K msg/s regardless of server speed. With TCP_NODELAY
	// all fragments are flushed immediately, so the server receives the
	// complete fragmented message in one RTT and the bench measures server
	// reassembly throughput rather than Nagle delay.
	dialer := &websocket.Dialer{
		Proxy:            http.ProxyFromEnvironment,
		HandshakeTimeout: 45 * time.Second,
		NetDial: func(network, addr string) (net.Conn, error) {
			conn, err := net.Dial(network, addr)
			if err != nil {
				return nil, err
			}
			if tc, ok := conn.(*net.TCPConn); ok {
				_ = tc.SetNoDelay(true)
			}
			return conn, nil
		},
	}
	// Skip TLS cert verification for wss because the bench cert is the
	// self-signed one shared by every engine; this matches k6's
	// `insecureSkipTLSVerify: true` in the script options.
	if *scheme == "wss" {
		dialer.TLSClientConfig = &tls.Config{InsecureSkipVerify: true}
	}

	switch *scenario {
	case "fragment-recv":
		runFragmentRecv(*name, u.String(), dialer, *vus, *duration, *bytes, *fragments)
	case "fragment-send":
		runFragmentSend(*name, u.String(), dialer, *vus, *duration, *bytes)
	case "deflate":
		// EnableCompression makes gorilla offer `permessage-deflate` in
		// the handshake. The dialer is cloned so the compressed and
		// uncompressed A/B legs do not share mutable state.
		deflateDialer := *dialer
		deflateDialer.EnableCompression = *compression
		runDeflate(*name, u.String(), &deflateDialer, *vus, *duration, *bytes)
	default:
		fmt.Fprintf(os.Stderr, "unknown scenario %q (expected fragment-recv|fragment-send|deflate)\n", *scenario)
		os.Exit(1)
	}
}

// runFragmentRecv: each VU repeatedly sends one fragmented message
// (split into `fragments` frames) and waits for the server to echo
// back a single coalesced message.
func runFragmentRecv(name, urlStr string, dialer *websocket.Dialer, vus int, duration time.Duration, totalBytes, fragments int) {
	stats := newStatsAggregator()
	ctx, cancel := context.WithTimeout(context.Background(), duration)
	defer cancel()

	frags := splitPayload(totalBytes, fragments)

	var wg sync.WaitGroup
	for i := 0; i < vus; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn, _, err := dialer.DialContext(ctx, urlStr, nil)
			if err != nil {
				return
			}
			defer conn.Close()

			for ctx.Err() == nil {
				start := time.Now()
				if err := writeFragmented(conn, frags); err != nil {
					return
				}
				if _, _, err := conn.ReadMessage(); err != nil {
					return
				}
				stats.add(time.Since(start))
			}
		}()
	}
	wg.Wait()

	emit(name, stats, duration)
}

// runFragmentSend: each VU sends a single un-fragmented binary message
// and reads the echo. The server may reply fragmented (continuation
// frames); gorilla coalesces continuation frames automatically when we
// call ReadMessage(), so the per-iteration cost reflects the server's
// fragment-emit path. (If the server doesn't fragment its echo, this
// scenario behaves identically to a vanilla ws-echo — the bench label
// just signals that fragmentation is acceptable on the receive side.)
func runFragmentSend(name, urlStr string, dialer *websocket.Dialer, vus int, duration time.Duration, totalBytes int) {
	stats := newStatsAggregator()
	ctx, cancel := context.WithTimeout(context.Background(), duration)
	defer cancel()

	payload := make([]byte, totalBytes)
	for i := range payload {
		payload[i] = 'x'
	}

	var wg sync.WaitGroup
	for i := 0; i < vus; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn, _, err := dialer.DialContext(ctx, urlStr, nil)
			if err != nil {
				return
			}
			defer conn.Close()

			for ctx.Err() == nil {
				start := time.Now()
				if err := conn.WriteMessage(websocket.BinaryMessage, payload); err != nil {
					return
				}
				if _, _, err := conn.ReadMessage(); err != nil {
					return
				}
				stats.add(time.Since(start))
			}
		}()
	}
	wg.Wait()

	emit(name, stats, duration)
}

// runDeflate: each VU opens a WebSocket connection (with permessage-
// deflate negotiated when the dialer's EnableCompression is set) and
// repeatedly echoes a synthetic compressible text payload. gorilla
// transparently compresses the sent message and decompresses the echo,
// so the per-iteration cost reflects the server's permessage-deflate
// decompress→recompress path under whatever bandwidth cap the harness
// applied. The payload length of the echo is verified against the sent
// length so a corrupt round-trip is not counted as throughput.
func runDeflate(name, urlStr string, dialer *websocket.Dialer, vus int, duration time.Duration, totalBytes int) {
	stats := newStatsAggregator()
	ctx, cancel := context.WithTimeout(context.Background(), duration)
	defer cancel()

	payload := compressiblePayload(totalBytes)

	var wg sync.WaitGroup
	for i := 0; i < vus; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			conn, _, err := dialer.DialContext(ctx, urlStr, nil)
			if err != nil {
				return
			}
			defer conn.Close()

			for ctx.Err() == nil {
				start := time.Now()
				if err := conn.WriteMessage(websocket.TextMessage, payload); err != nil {
					return
				}
				_, echo, err := conn.ReadMessage()
				if err != nil {
					return
				}
				if len(echo) != len(payload) {
					// Length mismatch means the decompress→recompress
					// round-trip corrupted the message; abort this VU
					// rather than count a bad iteration.
					return
				}
				stats.add(time.Since(start))
			}
		}()
	}
	wg.Wait()

	emit(name, stats, duration)
}

// compressiblePayload builds an `n`-byte blob of JSON-like records with
// per-record variation — incrementing ids / sequence numbers /
// timestamps and a rotating set of message bodies. The shared structure
// (field names, braces, quoting) gives DEFLATE redundancy to exploit,
// while the varying values keep the ratio realistic — ~6:1 at the 4 KiB
// default, representative of structured JSON / telemetry traffic, rather
// than the ~20:1+ a single repeated block would collapse to. This
// approximates real WebSocket traffic — chat / telemetry / log lines.
func compressiblePayload(n int) []byte {
	bodies := []string{
		"the quick brown fox jumps over the lazy dog",
		"lorem ipsum dolor sit amet consectetur adipiscing elit",
		"connection established and the handshake completed",
		"user joined the channel and started a new session",
		"telemetry sample recorded at the current interval",
	}
	var b strings.Builder
	b.Grow(n + 256)
	for seq := 1; b.Len() < n; seq++ {
		fmt.Fprintf(&b,
			`{"id":%d,"user":"client-%d","event":"message","channel":"room-%d",`+
				`"ts":%d,"body":"%s","seq":%d,"ok":true},`,
			4815162342+int64(seq), seq%97, seq%13,
			1700000000+seq, bodies[seq%len(bodies)], seq)
	}
	return []byte(b.String()[:n])
}

// writeFragmented sends a logical text message split into multiple
// frames using gorilla's NextWriter API: the first frame is opcode
// TEXT with fin=false (controlled by NOT calling Close), each call to
// NextWriter returning a continuation. gorilla's WriteMessage doesn't
// expose fin control, but using a *Writer with multiple Write() calls
// and a final Close() emits exactly fragments+1 frames? Actually no —
// gorilla coalesces all writes into a single Write+Close which sends
// one frame. We use the lower-level NextWriter loop with explicit
// `Close` after each fragment forces a separate frame.
//
// Re-verifying gorilla/websocket internals: NextWriter returns a
// io.WriteCloser; calling Close finalises one frame. So to fragment we
// must call NextWriter+Write+Close per fragment, and gorilla
// internally emits frame N as continuation when the previous frame
// ended without fin=true. Trick: we set the message type only on the
// first call (TEXT), and gorilla auto-uses CONTINUATION for subsequent
// fragments of the same logical message — except gorilla doesn't have
// that mode. Workaround: use PreparedMessage or write raw frames via
// the underlying conn.UnderlyingConn().
//
// Pragmatic implementation: use the lower-level WriteFrame from
// gorilla's internals (not exposed). Falling back to a manual frame
// builder over the underlying TCP connection is messy. Simpler: send
// the first fragment via WriteMessage with the TEXT opcode (which
// implicitly sets fin=true), then send continuation frames... no, that
// breaks the protocol.
//
// Real solution: gorilla's `Conn.WriteMessage` doesn't expose
// fragmentation, so we write our own frame encoder over the upgraded
// TCP socket. Implemented in `writeWebSocketFrame`.
// RFC 6455 opcode constants. gorilla/websocket exports `TextMessage` (1)
// and `BinaryMessage` (2) but keeps `continuationFrame` (0) unexported,
// so define the literal here.
const (
	opcodeContinuation = 0x0
	opcodeText         = 0x1
	opcodeBinary       = 0x2
)

func writeFragmented(conn *websocket.Conn, frags [][]byte) error {
	for i, f := range frags {
		opcode := byte(opcodeContinuation)
		if i == 0 {
			opcode = byte(opcodeText)
		}
		fin := i == len(frags)-1
		if err := writeWebSocketFrame(conn, opcode, fin, f); err != nil {
			return err
		}
	}
	return nil
}

// writeWebSocketFrame writes a single client-to-server WebSocket frame
// directly to the underlying TCP connection, with fin/opcode/payload
// fully controllable. The mask is constant zeroes (still RFC-compliant
// — masking is required from client to server, but the spec doesn't
// require the mask to be cryptographically random, only that it's
// applied; here we apply an identity mask, which is a no-op XOR but
// still includes the mask bit and 4-byte mask key in the frame header
// so servers process it as a properly-masked frame).
func writeWebSocketFrame(conn *websocket.Conn, opcode byte, fin bool, payload []byte) error {
	netConn := conn.UnderlyingConn()
	finBit := byte(0)
	if fin {
		finBit = 0x80
	}
	header := []byte{finBit | (opcode & 0x0f)}

	plen := len(payload)
	switch {
	case plen <= 125:
		header = append(header, 0x80|byte(plen)) // mask bit set
	case plen <= 0xFFFF:
		header = append(header, 0x80|126,
			byte(plen>>8), byte(plen&0xff))
	default:
		header = append(header, 0x80|127,
			byte(plen>>56), byte(plen>>48), byte(plen>>40), byte(plen>>32),
			byte(plen>>24), byte(plen>>16), byte(plen>>8), byte(plen&0xff))
	}
	// Identity mask (zero key) — masking is required client-to-server
	// but the key value is unconstrained; identity preserves the
	// payload bytes on the wire while satisfying the protocol.
	header = append(header, 0, 0, 0, 0)

	if _, err := netConn.Write(header); err != nil {
		return err
	}
	if plen > 0 {
		if _, err := netConn.Write(payload); err != nil {
			return err
		}
	}
	return nil
}

func splitPayload(total, frags int) [][]byte {
	parts := make([][]byte, frags)
	per := total / frags
	for i := 0; i < frags-1; i++ {
		parts[i] = bytesOf(per)
	}
	parts[frags-1] = bytesOf(total - per*(frags-1))
	return parts
}

func bytesOf(n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = 'x'
	}
	return b
}

// statsAggregator collects per-iteration round-trip durations from
// concurrent VUs and produces median / p99 percentiles + overall RPS.
type statsAggregator struct {
	mu     sync.Mutex
	rtts   []time.Duration
	totalN uint64
}

func newStatsAggregator() *statsAggregator {
	return &statsAggregator{rtts: make([]time.Duration, 0, 65536)}
}

func (s *statsAggregator) add(d time.Duration) {
	atomic.AddUint64(&s.totalN, 1)
	s.mu.Lock()
	s.rtts = append(s.rtts, d)
	s.mu.Unlock()
}

func emit(name string, stats *statsAggregator, duration time.Duration) {
	stats.mu.Lock()
	rtts := append([]time.Duration{}, stats.rtts...)
	stats.mu.Unlock()
	if len(rtts) == 0 {
		fmt.Printf("%s|0||\n", name)
		return
	}
	sort.Slice(rtts, func(i, j int) bool { return rtts[i] < rtts[j] })
	rps := float64(len(rtts)) / duration.Seconds()
	p50 := rtts[len(rtts)*50/100]
	p99 := rtts[len(rtts)*99/100]
	fmt.Printf("%s|%.6f|%s|%s\n", name, rps, formatDuration(p50), formatDuration(p99))
}

func formatDuration(d time.Duration) string {
	switch {
	case d >= time.Second:
		return fmt.Sprintf("%.2fs", d.Seconds())
	case d >= time.Millisecond:
		return fmt.Sprintf("%.2fms", float64(d)/float64(time.Millisecond))
	case d >= time.Microsecond:
		return fmt.Sprintf("%.2fµs", float64(d)/float64(time.Microsecond))
	default:
		return fmt.Sprintf("%dns", d.Nanoseconds())
	}
}
