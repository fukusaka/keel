// Minimal Go Gin HTTP server for benchmarking.
// Endpoints: GET /hello (13 bytes), GET /large (100KB)
//
// CLI: --port=8080 --profile=default|tuned|keel-equiv-0.1
//      --show-config --connection-close=true
//      --tcp-nodelay=true --reuse-address=true
//      --send-buffer=N --receive-buffer=N --threads=N
//      (--backlog is parsed but not applied; Go uses SOMAXCONN)
package main

import (
	"context"
	"fmt"
	"net"
	"os"
	"runtime"
	"strconv"
	"strings"
	"syscall"

	"github.com/gin-gonic/gin"
)

var largePayload = strings.Repeat("x", 102400)

// Config mirrors the JVM BenchmarkConfig for consistent cross-language comparison.
type Config struct {
	Port            int
	Profile         string
	ShowConfig      bool
	ConnectionClose bool
	Socket          SocketConfig
}

type SocketConfig struct {
	TcpNoDelay    *bool
	ReuseAddress  *bool
	Backlog       *int
	SendBuffer    *int
	ReceiveBuffer *int
	Threads       *int
}

type OsDefaults struct {
	TcpNoDelay    bool
	ReuseAddress  bool
	SendBuffer    int
	ReceiveBuffer int
}

func detectOsDefaults() OsDefaults {
	fd, err := syscall.Socket(syscall.AF_INET, syscall.SOCK_STREAM, syscall.IPPROTO_TCP)
	if err != nil {
		return OsDefaults{}
	}
	defer syscall.Close(fd)

	nodelay, _ := syscall.GetsockoptInt(fd, syscall.IPPROTO_TCP, syscall.TCP_NODELAY)
	reuse, _ := syscall.GetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_REUSEADDR)
	sndbuf, _ := syscall.GetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_SNDBUF)
	rcvbuf, _ := syscall.GetsockoptInt(fd, syscall.SOL_SOCKET, syscall.SO_RCVBUF)

	return OsDefaults{
		TcpNoDelay:    nodelay != 0,
		ReuseAddress:  reuse != 0,
		SendBuffer:    sndbuf,
		ReceiveBuffer: rcvbuf,
	}
}

func parseConfig() Config {
	cfg := Config{
		Port:    8080,
		Profile: "default",
	}

	for _, arg := range os.Args[1:] {
		if arg == "--show-config" {
			cfg.ShowConfig = true
			continue
		}
		if !strings.HasPrefix(arg, "--") || !strings.Contains(arg, "=") {
			continue
		}
		parts := strings.SplitN(strings.TrimPrefix(arg, "--"), "=", 2)
		key, value := parts[0], parts[1]

		switch key {
		case "port":
			cfg.Port, _ = strconv.Atoi(value)
		case "profile":
			cfg.Profile = value
		case "connection-close":
			v, _ := strconv.ParseBool(value)
			cfg.ConnectionClose = v
		case "tcp-nodelay":
			v, _ := strconv.ParseBool(value)
			cfg.Socket.TcpNoDelay = &v
		case "reuse-address":
			v, _ := strconv.ParseBool(value)
			cfg.Socket.ReuseAddress = &v
		case "backlog":
			v, _ := strconv.Atoi(value)
			cfg.Socket.Backlog = &v
		case "send-buffer":
			v, _ := strconv.Atoi(value)
			cfg.Socket.SendBuffer = &v
		case "receive-buffer":
			v, _ := strconv.Atoi(value)
			cfg.Socket.ReceiveBuffer = &v
		case "threads":
			v, _ := strconv.Atoi(value)
			cfg.Socket.Threads = &v
		}
	}

	cfg.applyProfile()
	return cfg
}

func (c *Config) applyProfile() {
	switch {
	case c.Profile == "default":
		// no overrides
	case c.Profile == "tuned":
		cpu := runtime.NumCPU()
		if c.Socket.TcpNoDelay == nil {
			v := true
			c.Socket.TcpNoDelay = &v
		}
		if c.Socket.ReuseAddress == nil {
			v := true
			c.Socket.ReuseAddress = &v
		}
		if c.Socket.Backlog == nil {
			v := 1024
			c.Socket.Backlog = &v
		}
		if c.Socket.Threads == nil {
			c.Socket.Threads = &cpu
		}
	case strings.HasPrefix(c.Profile, "keel-equiv"):
		c.ConnectionClose = true
	default:
		fmt.Fprintf(os.Stderr, "Unknown profile: %s\n", c.Profile)
		fmt.Fprintf(os.Stderr, "Available: default, tuned, keel-equiv-<version>\n")
		os.Exit(1)
	}
}

func (c *Config) display() string {
	osDefaults := detectOsDefaults()
	cpu := runtime.NumCPU()
	s := c.Socket

	var b strings.Builder
	f := func(label, value string) {
		fmt.Fprintf(&b, "  %-22s %s\n", label, value)
	}

	b.WriteString("=== Benchmark Configuration ===\n")
	f("server:", "go-hello")
	f("port:", strconv.Itoa(c.Port))
	f("profile:", c.Profile)
	f("cpu-cores:", strconv.Itoa(cpu))
	b.WriteString("\n")

	b.WriteString("--- Connection ---\n")
	f("connection-close:", strconv.FormatBool(c.ConnectionClose))
	b.WriteString("\n")

	b.WriteString("--- Socket Options ---\n")
	f("tcp-nodelay:", optOrDefault(s.TcpNoDelay, fmt.Sprintf("%v (default by OS)", osDefaults.TcpNoDelay)))
	f("reuse-address:", optOrDefault(s.ReuseAddress, fmt.Sprintf("%v (default by OS)", osDefaults.ReuseAddress)))
	// Go's net.ListenConfig does not expose backlog; uses SOMAXCONN internally
	if s.Backlog != nil {
		f("backlog:", fmt.Sprintf("%d (accepted, not applied; Go uses SOMAXCONN)", *s.Backlog))
	} else {
		f("backlog:", "(not configurable, Go uses SOMAXCONN)")
	}
	f("send-buffer:", optOrDefaultBytes(s.SendBuffer, fmt.Sprintf("%d bytes (default by OS)", osDefaults.SendBuffer)))
	f("receive-buffer:", optOrDefaultBytes(s.ReceiveBuffer, fmt.Sprintf("%d bytes (default by OS)", osDefaults.ReceiveBuffer)))

	threadsDisplay := fmt.Sprintf("%d (default by Go, GOMAXPROCS)", runtime.GOMAXPROCS(0))
	if s.Threads != nil {
		if *s.Threads == cpu {
			threadsDisplay = fmt.Sprintf("%d (tuned: cpu-cores)", *s.Threads)
		} else {
			threadsDisplay = strconv.Itoa(*s.Threads)
		}
	}
	f("threads:", threadsDisplay)
	b.WriteString("\n")

	b.WriteString("--- Engine-Specific (go-hello) ---\n")
	f("(all via socket options)", "")

	return b.String()
}

func optOrDefault(v *bool, def string) string {
	if v != nil {
		return strconv.FormatBool(*v)
	}
	return def
}

func optOrDefaultInt(v *int, def string) string {
	if v != nil {
		return strconv.Itoa(*v)
	}
	return def
}

func optOrDefaultBytes(v *int, def string) string {
	if v != nil {
		return fmt.Sprintf("%d bytes", *v)
	}
	return def
}

func main() {
	cfg := parseConfig()

	if cfg.ShowConfig {
		fmt.Print(cfg.display())
		return
	}

	// Apply thread count via GOMAXPROCS
	if cfg.Socket.Threads != nil {
		runtime.GOMAXPROCS(*cfg.Socket.Threads)
	}

	gin.SetMode(gin.ReleaseMode)
	r := gin.New()

	if cfg.ConnectionClose {
		r.Use(func(c *gin.Context) {
			c.Header("Connection", "close")
			c.Next()
		})
	}

	r.GET("/hello", func(c *gin.Context) {
		c.String(200, "Hello, World!")
	})
	r.GET("/large", func(c *gin.Context) {
		c.String(200, largePayload)
	})

	// Create listener with socket options
	lc := net.ListenConfig{
		Control: func(network, address string, c syscall.RawConn) error {
			return c.Control(func(fd uintptr) {
				if cfg.Socket.TcpNoDelay != nil {
					v := 0
					if *cfg.Socket.TcpNoDelay {
						v = 1
					}
					syscall.SetsockoptInt(int(fd), syscall.IPPROTO_TCP, syscall.TCP_NODELAY, v)
				}
				if cfg.Socket.ReuseAddress != nil {
					v := 0
					if *cfg.Socket.ReuseAddress {
						v = 1
					}
					syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_REUSEADDR, v)
				}
				if cfg.Socket.SendBuffer != nil {
					syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_SNDBUF, *cfg.Socket.SendBuffer)
				}
				if cfg.Socket.ReceiveBuffer != nil {
					syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_RCVBUF, *cfg.Socket.ReceiveBuffer)
				}
			})
		},
	}

	addr := fmt.Sprintf(":%d", cfg.Port)
	listener, err := lc.Listen(context.Background(), "tcp", addr)
	if err != nil {
		fmt.Fprintf(os.Stderr, "Failed to listen: %v\n", err)
		os.Exit(1)
	}

	fmt.Printf("Go Gin server started on port %d\n", cfg.Port)
	r.RunListener(listener)
}
