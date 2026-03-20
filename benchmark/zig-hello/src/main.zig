// Minimal Zig HTTP server for benchmarking using std.http.Server.
// Endpoints: GET /hello (13 bytes), GET /large (100KB)
//
// CLI: --port=8080 --profile=default|tuned|keel-equiv-0.1
//      --show-config --connection-close=true
//      --tcp-nodelay=true --reuse-address=true --backlog=N
//      --send-buffer=N --receive-buffer=N --threads=N
//      --read-buffer=N --write-buffer=N

const std = @import("std");
const net = std.net;
const http = std.http;
const posix = std.posix;

const large_payload: []const u8 = "x" ** 102_400;

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

    fn parse(allocator: std.mem.Allocator) Config {
        const args = std.process.argsAlloc(allocator) catch return .{};
        defer std.process.argsFree(allocator, args);

        var cfg = Config{};
        for (args[1..]) |arg| {
            if (std.mem.eql(u8, arg, "--show-config")) {
                cfg.show_config = true;
                continue;
            }
            const stripped = if (std.mem.startsWith(u8, arg, "--")) arg[2..] else continue;
            const eq_pos = std.mem.indexOf(u8, stripped, "=") orelse continue;
            const key = stripped[0..eq_pos];
            const value = stripped[eq_pos + 1 ..];

            if (std.mem.eql(u8, key, "port")) {
                cfg.port = std.fmt.parseInt(u16, value, 10) catch 8080;
            } else if (std.mem.eql(u8, key, "profile")) {
                cfg.profile = allocator.dupe(u8, value) catch "default";
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
        const os_defaults = detectOsDefaults();
        const p = std.debug.print;

        p("=== Benchmark Configuration ===\n", .{});
        p("  {s:<22} {s}\n", .{ "server:", "zig-hello" });
        p("  {s:<22} {d}\n", .{ "port:", self.port });
        p("  {s:<22} {s}\n", .{ "profile:", self.profile });
        p("  {s:<22} {d}\n", .{ "cpu-cores:", cpu });
        p("\n", .{});

        p("--- Connection ---\n", .{});
        p("  {s:<22} {}\n", .{ "connection-close:", self.connection_close });
        p("\n", .{});

        p("--- Socket Options ---\n", .{});
        if (self.tcp_nodelay) |v| p("  {s:<22} {}\n", .{ "tcp-nodelay:", v }) else p("  {s:<22} {} (default by OS)\n", .{ "tcp-nodelay:", os_defaults.tcp_nodelay });
        if (self.reuse_address) |v| p("  {s:<22} {}\n", .{ "reuse-address:", v }) else p("  {s:<22} {} (default by OS)\n", .{ "reuse-address:", os_defaults.reuse_address });
        if (self.backlog) |v| p("  {s:<22} {d}\n", .{ "backlog:", v }) else p("  {s:<22} 128 (default by Zig)\n", .{"backlog:"});
        if (self.send_buffer) |v| p("  {s:<22} {d} bytes\n", .{ "send-buffer:", v }) else p("  {s:<22} {d} bytes (default by OS)\n", .{ "send-buffer:", os_defaults.send_buffer });
        if (self.receive_buffer) |v| p("  {s:<22} {d} bytes\n", .{ "receive-buffer:", v }) else p("  {s:<22} {d} bytes (default by OS)\n", .{ "receive-buffer:", os_defaults.receive_buffer });
        if (self.threads) |t| {
            if (t == cpu) p("  {s:<22} {d} (tuned: cpu-cores)\n", .{ "threads:", t }) else p("  {s:<22} {d}\n", .{ "threads:", t });
        } else {
            p("  {s:<22} {d} (default, cpu-cores)\n", .{ "threads:", cpu });
        }
        p("\n", .{});

        p("--- Engine-Specific (zig-hello) ---\n", .{});
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

const TCP_NODELAY: u32 = 0x01; // platform-specific: macOS=0x01, Linux=1

const OsDefaults = struct {
    tcp_nodelay: bool,
    reuse_address: bool,
    send_buffer: u32,
    receive_buffer: u32,
};

fn detectOsDefaults() OsDefaults {
    const fd = posix.socket(posix.AF.INET, posix.SOCK.STREAM | posix.SOCK.CLOEXEC, posix.IPPROTO.TCP) catch return .{
        .tcp_nodelay = false,
        .reuse_address = false,
        .send_buffer = 0,
        .receive_buffer = 0,
    };
    defer posix.close(fd);

    return .{
        .tcp_nodelay = getIntOpt(fd, posix.IPPROTO.TCP, TCP_NODELAY) != 0,
        .reuse_address = getIntOpt(fd, posix.SOL.SOCKET, posix.SO.REUSEADDR) != 0,
        .send_buffer = @intCast(getIntOpt(fd, posix.SOL.SOCKET, posix.SO.SNDBUF)),
        .receive_buffer = @intCast(getIntOpt(fd, posix.SOL.SOCKET, posix.SO.RCVBUF)),
    };
}

fn getIntOpt(fd: posix.socket_t, level: i32, optname: u32) i32 {
    var buf: [@sizeOf(c_int)]u8 = undefined;
    posix.getsockopt(fd, level, optname, &buf) catch return 0;
    return std.mem.bytesToValue(c_int, &buf);
}

fn setIntOpt(fd: posix.socket_t, level: i32, optname: u32, value: i32) void {
    posix.setsockopt(fd, level, optname, &std.mem.toBytes(value)) catch {};
}

pub fn main() !void {
    var gpa = std.heap.GeneralPurposeAllocator(.{}){};
    const allocator = gpa.allocator();

    var cfg = Config.parse(allocator);

    if (cfg.show_config) {
        cfg.display();
        return;
    }

    const address = net.Address.initIp4(.{ 0, 0, 0, 0 }, cfg.port);
    var server = try address.listen(.{
        .kernel_backlog = cfg.backlog orelse 128,
        .reuse_address = cfg.reuse_address orelse false,
    });

    // Apply socket options after listen
    const fd = server.stream.handle;
    if (cfg.tcp_nodelay) |v| {
        setIntOpt(fd, posix.IPPROTO.TCP, TCP_NODELAY, if (v) 1 else 0);
    }
    if (cfg.send_buffer) |v| {
        setIntOpt(fd, posix.SOL.SOCKET, posix.SO.SNDBUF, @intCast(v));
    }
    if (cfg.receive_buffer) |v| {
        setIntOpt(fd, posix.SOL.SOCKET, posix.SO.RCVBUF, @intCast(v));
    }

    std.debug.print("Zig std.http server started on port {d}\n", .{cfg.port});

    const read_buf_size = cfg.read_buffer orelse 8192;
    const write_buf_size = cfg.write_buffer orelse 8192;
    const keep_alive = !cfg.connection_close;

    while (true) {
        const conn = server.accept() catch continue;
        _ = std.Thread.spawn(.{}, handleConnection, .{ conn, read_buf_size, write_buf_size, keep_alive }) catch {
            conn.stream.close();
            continue;
        };
    }
}

fn handleConnection(conn: net.Server.Connection, read_buf_size: usize, write_buf_size: usize, keep_alive: bool) void {
    defer conn.stream.close();

    // Allocate buffers dynamically based on config
    var gpa = std.heap.GeneralPurposeAllocator(.{}){};
    const alloc = gpa.allocator();
    const read_buf = alloc.alloc(u8, read_buf_size) catch return;
    defer alloc.free(read_buf);
    const write_buf = alloc.alloc(u8, write_buf_size) catch return;
    defer alloc.free(write_buf);

    var reader = conn.stream.reader(read_buf);
    var writer = conn.stream.writer(write_buf);
    var srv = http.Server.init(reader.interface(), &writer.interface);

    while (true) {
        var req = srv.receiveHead() catch return;
        const target = req.head.target;

        if (std.mem.eql(u8, target, "/hello")) {
            req.respond("Hello, World!", .{ .keep_alive = keep_alive }) catch return;
        } else if (std.mem.eql(u8, target, "/large")) {
            req.respond(large_payload, .{ .keep_alive = keep_alive }) catch return;
        } else {
            req.respond("Not Found", .{ .status = .not_found, .keep_alive = keep_alive }) catch return;
        }
    }
}
