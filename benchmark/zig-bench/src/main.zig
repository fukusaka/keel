// Minimal Zig HTTP server for benchmarking using std.http.Server (Zig 0.16+).
// Endpoints:
//   GET  /hello                       (13 bytes)
//   GET  /large                       (100KB)
//   POST /upload-stream               (drains body, replies with byte count)
//   GET  /sse-stream?count=N&size=M   (chunked SSE frames)
//   WS   /ws-echo                     (echo every frame back)
//
// CLI: --port=8080 --profile=default|tuned|keel-equiv-0.1
//      --show-config --connection-close=true
//      --tcp-nodelay=true --reuse-address=true --backlog=N
//      --send-buffer=N --receive-buffer=N --threads=N
//      --read-buffer=N --write-buffer=N
//
// Migration note (Zig 0.15 -> 0.16):
//   * `std.net` -> `std.Io.net` (TCP listen / accept go through `Io`).
//   * `std.heap.GeneralPurposeAllocator` -> `std.heap.DebugAllocator`
//     (or `init.gpa` from the new `std.process.Init` entry-point).
//   * `pub fn main()` -> `pub fn main(init: std.process.Init) !void` so the
//     program receives an `Io` and an arena for `args.toSlice`.
//   * Per-socket options (`SO_SNDBUF`, `SO_RCVBUF`, `TCP_NODELAY`) are still
//     reachable via `std.posix.setsockopt` on `Stream.socket.handle`.

const std = @import("std");
const builtin = @import("builtin");
const http = std.http;
const net = std.Io.net;
const posix = std.posix;
const Io = std.Io;

const large_payload: []const u8 = "x" ** 102_400;
const upload_ack: []const u8 = "ok";
const sse_default_count: usize = 100;
const sse_default_size: usize = 1024;
const sse_max_size: usize = 65_536;

const Config = struct {
    port: u16 = 8080,
    profile: []const u8 = "default",
    show_config: bool = false,
    connection_close: bool = false,
    tcp_nodelay: ?bool = null,
    reuse_address: ?bool = null,
    backlog: ?u31 = null,
    send_buffer: ?u32 = null,
    receive_buffer: ?u32 = null,
    threads: ?usize = null,
    read_buffer: ?usize = null,
    write_buffer: ?usize = null,

    fn parse(args: []const [:0]const u8) Config {
        var cfg = Config{};
        // Skip args[0] (the program name).
        for (args[1..]) |arg| {
            if (std.mem.eql(u8, arg, "--show-config")) {
                cfg.show_config = true;
                continue;
            }
            const stripped = if (std.mem.startsWith(u8, arg, "--")) arg[2..] else continue;
            const eq_pos = std.mem.indexOfScalar(u8, stripped, '=') orelse continue;
            const key = stripped[0..eq_pos];
            const value = stripped[eq_pos + 1 ..];

            if (std.mem.eql(u8, key, "port")) {
                cfg.port = std.fmt.parseInt(u16, value, 10) catch 8080;
            } else if (std.mem.eql(u8, key, "profile")) {
                cfg.profile = value;
            } else if (std.mem.eql(u8, key, "connection-close")) {
                cfg.connection_close = parseBool(value);
            } else if (std.mem.eql(u8, key, "tcp-nodelay")) {
                cfg.tcp_nodelay = parseBool(value);
            } else if (std.mem.eql(u8, key, "reuse-address")) {
                cfg.reuse_address = parseBool(value);
            } else if (std.mem.eql(u8, key, "backlog")) {
                cfg.backlog = std.fmt.parseInt(u31, value, 10) catch null;
            } else if (std.mem.eql(u8, key, "send-buffer")) {
                cfg.send_buffer = std.fmt.parseInt(u32, value, 10) catch null;
            } else if (std.mem.eql(u8, key, "receive-buffer")) {
                cfg.receive_buffer = std.fmt.parseInt(u32, value, 10) catch null;
            } else if (std.mem.eql(u8, key, "threads")) {
                cfg.threads = std.fmt.parseInt(usize, value, 10) catch null;
            } else if (std.mem.eql(u8, key, "read-buffer")) {
                cfg.read_buffer = std.fmt.parseInt(usize, value, 10) catch null;
            } else if (std.mem.eql(u8, key, "write-buffer")) {
                cfg.write_buffer = std.fmt.parseInt(usize, value, 10) catch null;
            }
        }

        cfg.applyProfile();
        return cfg;
    }

    fn applyProfile(self: *Config) void {
        if (std.mem.eql(u8, self.profile, "default")) {
            return;
        } else if (std.mem.eql(u8, self.profile, "tuned")) {
            const cpu = cpuCores();
            if (self.tcp_nodelay == null) self.tcp_nodelay = true;
            if (self.reuse_address == null) self.reuse_address = true;
            if (self.backlog == null) self.backlog = 1024;
            if (self.threads == null) self.threads = cpu;
            if (self.read_buffer == null) self.read_buffer = 16384;
            if (self.write_buffer == null) self.write_buffer = 16384;
        } else if (std.mem.startsWith(u8, self.profile, "keel-equiv")) {
            self.connection_close = true;
        } else {
            std.debug.print("Unknown profile: {s}\n", .{self.profile});
            std.debug.print("Available: default, tuned, keel-equiv-<version>\n", .{});
            std.process.exit(1);
        }
    }

    fn display(self: *const Config) void {
        const cpu = cpuCores();
        const p = std.debug.print;

        p("=== Benchmark Configuration ===\n", .{});
        p("  {s:<22} {s}\n", .{ "server:", "zig-bench" });
        p("  {s:<22} {d}\n", .{ "port:", self.port });
        p("  {s:<22} {s}\n", .{ "profile:", self.profile });
        p("  {s:<22} {d}\n", .{ "cpu-cores:", cpu });
        p("\n", .{});

        p("--- Connection ---\n", .{});
        p("  {s:<22} {}\n", .{ "connection-close:", self.connection_close });
        p("\n", .{});

        p("--- Socket Options ---\n", .{});
        if (self.tcp_nodelay) |v| p("  {s:<22} {}\n", .{ "tcp-nodelay:", v }) else p("  {s:<22} (default by OS)\n", .{"tcp-nodelay:"});
        if (self.reuse_address) |v| p("  {s:<22} {}\n", .{ "reuse-address:", v }) else p("  {s:<22} false (default by std.Io.net)\n", .{"reuse-address:"});
        if (self.backlog) |v| p("  {s:<22} {d}\n", .{ "backlog:", v }) else p("  {s:<22} 128 (default by std.Io.net)\n", .{"backlog:"});
        if (self.send_buffer) |v| p("  {s:<22} {d} bytes\n", .{ "send-buffer:", v }) else p("  {s:<22} (default by OS)\n", .{"send-buffer:"});
        if (self.receive_buffer) |v| p("  {s:<22} {d} bytes\n", .{ "receive-buffer:", v }) else p("  {s:<22} (default by OS)\n", .{"receive-buffer:"});
        if (self.threads) |t| {
            if (t == cpu) p("  {s:<22} {d} (tuned: cpu-cores)\n", .{ "threads:", t }) else p("  {s:<22} {d}\n", .{ "threads:", t });
        } else {
            p("  {s:<22} {d} (default, cpu-cores)\n", .{ "threads:", cpu });
        }
        p("\n", .{});

        p("--- Engine-Specific (zig-bench) ---\n", .{});
        if (self.read_buffer) |v| p("  {s:<22} {d}\n", .{ "read-buffer:", v }) else p("  {s:<22} 8192 (default)\n", .{"read-buffer:"});
        if (self.write_buffer) |v| p("  {s:<22} {d}\n", .{ "write-buffer:", v }) else p("  {s:<22} 8192 (default)\n", .{"write-buffer:"});
    }
};

fn parseBool(value: []const u8) bool {
    return std.mem.eql(u8, value, "true");
}

fn cpuCores() usize {
    return std.Thread.getCpuCount() catch 1;
}

// macOS / Linux TCP_NODELAY value (constant 1 in IPPROTO_TCP).
const TCP_NODELAY: u32 = 0x01;

fn setIntOpt(fd: posix.socket_t, level: i32, optname: u32, value: i32) void {
    posix.setsockopt(fd, level, optname, &std.mem.toBytes(value)) catch {};
}

pub fn main(init: std.process.Init) !void {
    const args = try init.minimal.args.toSlice(init.arena.allocator());
    var cfg = Config.parse(args);

    if (cfg.show_config) {
        cfg.display();
        return;
    }

    const io = init.io;
    const address = try net.IpAddress.parseIp4("0.0.0.0", cfg.port);
    var server = try address.listen(io, .{
        .kernel_backlog = cfg.backlog orelse 128,
        .reuse_address = cfg.reuse_address orelse false,
    });
    defer server.deinit(io);

    std.debug.print("Zig std.http server started on port {d}\n", .{cfg.port});

    const read_buf_size = cfg.read_buffer orelse 8192;
    const write_buf_size = cfg.write_buffer orelse 8192;
    const keep_alive = !cfg.connection_close;

    while (true) {
        const stream = server.accept(io) catch |err| switch (err) {
            error.WouldBlock, error.ConnectionAborted => continue,
            else => return err,
        };

        // Apply per-connection socket options. std.Io.net's high-level API
        // doesn't surface TCP_NODELAY / SO_SNDBUF / SO_RCVBUF, but the
        // underlying fd is reachable via Stream.socket.handle and
        // std.posix.setsockopt is still available.
        if (cfg.tcp_nodelay) |v| {
            setIntOpt(stream.socket.handle, posix.IPPROTO.TCP, TCP_NODELAY, if (v) 1 else 0);
        }
        if (cfg.send_buffer) |v| {
            setIntOpt(stream.socket.handle, posix.SOL.SOCKET, posix.SO.SNDBUF, @intCast(v));
        }
        if (cfg.receive_buffer) |v| {
            setIntOpt(stream.socket.handle, posix.SOL.SOCKET, posix.SO.RCVBUF, @intCast(v));
        }

        const ctx = ConnectionContext{
            .stream = stream,
            .io = io,
            .gpa = init.gpa,
            .read_buf_size = read_buf_size,
            .write_buf_size = write_buf_size,
            .keep_alive = keep_alive,
        };
        _ = std.Thread.spawn(.{}, handleConnection, .{ctx}) catch {
            stream.close(io);
            continue;
        };
    }
}

const ConnectionContext = struct {
    stream: net.Stream,
    io: Io,
    gpa: std.mem.Allocator,
    read_buf_size: usize,
    write_buf_size: usize,
    keep_alive: bool,
};

fn handleConnection(ctx: ConnectionContext) void {
    defer ctx.stream.close(ctx.io);

    const read_buf = ctx.gpa.alloc(u8, ctx.read_buf_size) catch return;
    defer ctx.gpa.free(read_buf);
    const write_buf = ctx.gpa.alloc(u8, ctx.write_buf_size) catch return;
    defer ctx.gpa.free(write_buf);

    var reader = ctx.stream.reader(ctx.io, read_buf);
    var writer = ctx.stream.writer(ctx.io, write_buf);
    var srv = http.Server.init(&reader.interface, &writer.interface);

    while (true) {
        var req = srv.receiveHead() catch return;
        const target = req.head.target;

        if (std.mem.eql(u8, target, "/hello")) {
            req.respond("Hello, World!", .{ .keep_alive = ctx.keep_alive }) catch return;
        } else if (std.mem.eql(u8, target, "/large")) {
            req.respond(large_payload, .{ .keep_alive = ctx.keep_alive }) catch return;
        } else if (std.mem.eql(u8, target, "/upload-stream")) {
            handleUploadStream(&req, ctx.keep_alive) catch return;
        } else if (std.mem.eql(u8, pathOnly(target), "/sse-stream")) {
            handleSseStream(&req, target, ctx.keep_alive) catch return;
        } else if (std.mem.eql(u8, target, "/ws-echo")) {
            handleWsEcho(&req) catch {};
            return;
        } else {
            req.respond("Not Found", .{ .status = .not_found, .keep_alive = ctx.keep_alive }) catch return;
        }
    }
}

/// Returns the path portion of a request target (everything before the
/// optional `?`), so `/sse-stream?count=10` matches the `/sse-stream` route.
fn pathOnly(target: []const u8) []const u8 {
    if (std.mem.indexOfScalar(u8, target, '?')) |q| return target[0..q];
    return target;
}

/// Drains the request body via std.http's body reader (no aggregation),
/// replies with the byte count in `X-Bytes-Received`. `discardRemaining`
/// returns the total bytes discarded so we don't need to manage a drain
/// buffer manually.
fn handleUploadStream(req: *http.Server.Request, keep_alive: bool) !void {
    var body_buf: [8192]u8 = undefined;
    const body_reader = req.readerExpectContinue(&body_buf) catch return;
    // discardRemaining swallows EndOfStream internally and returns the
    // total bytes discarded. ShortError is declared with EOS + ReadFailed,
    // so be defensive and abandon the request on any error.
    const received = body_reader.discardRemaining() catch return;

    var count_buf: [32]u8 = undefined;
    const count_str = try std.fmt.bufPrint(&count_buf, "{d}", .{received});
    const headers = [_]http.Header{
        .{ .name = "X-Bytes-Received", .value = count_str },
    };
    try req.respond(upload_ack, .{ .keep_alive = keep_alive, .extra_headers = &headers });
}

/// Emits N SSE frames of M bytes each via chunked Transfer-Encoding,
/// using `respondStreaming` so the body writer handles framing.
fn handleSseStream(req: *http.Server.Request, target: []const u8, keep_alive: bool) !void {
    var count = sse_default_count;
    var size = sse_default_size;
    if (std.mem.indexOfScalar(u8, target, '?')) |q| {
        var it = std.mem.splitScalar(u8, target[q + 1 ..], '&');
        while (it.next()) |pair| {
            const eq = std.mem.indexOfScalar(u8, pair, '=') orelse continue;
            const key = pair[0..eq];
            const value = pair[eq + 1 ..];
            if (std.mem.eql(u8, key, "count")) {
                count = std.fmt.parseInt(usize, value, 10) catch sse_default_count;
            } else if (std.mem.eql(u8, key, "size")) {
                size = std.fmt.parseInt(usize, value, 10) catch sse_default_size;
            }
        }
    }
    if (size > sse_max_size) size = sse_max_size;

    // Use a small (256-byte) stream_buf so frames cannot accumulate inside
    // it before the per-frame `flush` below; the previous 16 KB buffer let
    // ~15 frames coalesce before the writer pushed a chunk to the wire,
    // inflating the SSE bench by the same factor relative to per-frame
    // engines (pipeline-http-* / ktor-keel-* / netty-raw).
    var stream_buf: [256]u8 = undefined;
    var body = req.respondStreaming(&stream_buf, .{
        .respond_options = .{
            .keep_alive = keep_alive,
            .extra_headers = &[_]http.Header{
                .{ .name = "content-type", .value = "text/event-stream" },
            },
        },
    }) catch return;

    // BodyWriter.writer is a field (not a method). splatBytesAll repeats
    // the byte sequence N times — used for the SSE frame's `x...` payload
    // so we don't have to allocate a per-frame buffer of `size` bytes.
    //
    // Per-frame flush: the 16 KB stream_buf above otherwise accumulates
    // ~15 frames before flushing to the wire, so an apparent throughput
    // ~15× the real per-frame number leaks into the SSE bench. Match the
    // per-frame send semantics that pipeline-http-* (PR #440 / K25),
    // ktor-keel-* (PR #441 / K29), and netty-raw (PR #442 / K30) enforce.
    const out = &body.writer;
    var i: usize = 0;
    while (i < count) : (i += 1) {
        try out.writeAll("data: ");
        try out.splatBytesAll("x", size);
        try out.writeAll("\n\n");
        try out.flush();
    }
    try body.end();
}

/// WebSocket echo handler. Uses Zig 0.15+ std.http.Server.WebSocket — the
/// HTTP/1.1 Upgrade is performed in-place via `respondWebSocket` and each
/// inbound text / binary message is reflected back.
///
/// **Fragmentation limitation**: Zig 0.16 std.http's `readSmallMessage`
/// explicitly returns `error.UnexpectedOpCode` for `continuation` frames
/// and `error.MessageOversize` for any non-final (`fin=false`) frame.
/// std.http does not yet expose a fragment-aware read API, so this
/// handler can only echo single-frame WebSocket messages. When the
/// client (e.g. `benchmark/wsbench/wsbench -scenario=fragment-recv`)
/// sends a fragmented message, `readSmallMessage` errors out and we
/// close the connection cleanly with a 1003 (`unsupported data`) close
/// frame so the client sees a deterministic failure instead of hanging.
/// Fragmented bench coverage for zig-bench is therefore N/A until
/// upstream std.http grows a streaming-message API.
fn handleWsEcho(req: *http.Server.Request) !void {
    const upgrade = req.upgradeRequested();
    const key = switch (upgrade) {
        .websocket => |k| k orelse return error.MissingWebSocketKey,
        else => return error.NotUpgradeRequest,
    };
    var ws = try req.respondWebSocket(.{ .key = key });
    // The upgrade response headers are buffered by `respondWebSocket`
    // and remain queued until something flushes the underlying writer
    // (writeMessage flushes after each frame, but the *initial*
    // 101 Switching Protocols + Sec-WebSocket-Accept stays in the
    // buffer otherwise — clients hang waiting for the handshake).
    try ws.output.flush();

    while (true) {
        const msg = ws.readSmallMessage() catch |err| switch (err) {
            error.ConnectionClose => return,
            error.UnexpectedOpCode, error.MessageOversize => {
                // Fragmented or oversize message — std.http can't
                // handle it. Send a 1003 (`unsupported data`) close
                // frame so the client doesn't hang, then bail.
                writeCloseFrame(&ws, 1003) catch {};
                return;
            },
            else => return,
        };
        switch (msg.opcode) {
            .text, .binary => {
                ws.writeMessage(msg.data, msg.opcode) catch return;
            },
            .ping => {
                // Reply with a pong carrying the same payload, per RFC 6455.
                ws.writeMessage(msg.data, .pong) catch return;
            },
            else => {},
        }
    }
}

/// Writes a WebSocket close frame (opcode 0x8) carrying the given
/// status code. Used to terminate the connection when std.http surfaces
/// an error that prevents normal echo (fragment / oversize).
fn writeCloseFrame(ws: *http.Server.WebSocket, code: u16) !void {
    var payload: [2]u8 = .{ @intCast(code >> 8), @intCast(code & 0xFF) };
    ws.writeMessage(&payload, .connection_close) catch {};
    ws.output.flush() catch {};
}
