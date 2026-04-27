// Minimal Swift Hummingbird 2 HTTP server for benchmarking.
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
//      --tls --tls-cert=PATH --tls-key=PATH
//      (tcp-nodelay/send-buffer/receive-buffer accepted but not applied; managed by SwiftNIO)

import Foundation
import HTTPTypes
import Hummingbird
import HummingbirdTLS
import HummingbirdWebSocket
import HummingbirdWSCompression
import NIOCore
import NIOPosix
import WSCore
#if canImport(Darwin)
import Darwin.C
#elseif canImport(Glibc)
import Glibc
#endif

let helloPayloadBytes: [UInt8] = Array("Hello, World!".utf8)
let largePayloadBytes: [UInt8] = Array(String(repeating: "x", count: 102_400).utf8)
let uploadAckBytes: [UInt8] = Array("ok".utf8)

let sseDefaultCount = 100
let sseDefaultSize = 1024

// MARK: - Configuration

struct BenchConfig {
    var port: Int = 8080
    var profile: String = "default"
    var showConfig: Bool = false
    var connectionClose: Bool = false
    // Socket options — all accepted via CLI for consistency across servers.
    // SwiftNIO manages tcp-nodelay/send-buffer/receive-buffer internally;
    // these are parsed and displayed but not applied.
    var tcpNoDelay: Bool? = nil
    var reuseAddress: Bool? = nil
    var backlog: Int? = nil
    var sendBuffer: Int? = nil
    var receiveBuffer: Int? = nil
    var threads: Int? = nil
    var tls: Bool = false
    var tlsCert: String = "benchmark/certs/server.crt"
    var tlsKey: String = "benchmark/certs/server.key"

    static func parse() -> BenchConfig {
        var cfg = BenchConfig()
        for arg in CommandLine.arguments.dropFirst() {
            if arg == "--show-config" {
                cfg.showConfig = true
                continue
            }
            if arg == "--tls" {
                cfg.tls = true
                continue
            }
            guard arg.hasPrefix("--"), let eqIdx = arg.firstIndex(of: "=") else { continue }
            let key = String(arg[arg.index(arg.startIndex, offsetBy: 2)..<eqIdx])
            let value = String(arg[arg.index(after: eqIdx)...])

            switch key {
            case "port": cfg.port = Int(value) ?? 8080
            case "profile": cfg.profile = value
            case "connection-close": cfg.connectionClose = value == "true"
            case "tcp-nodelay": cfg.tcpNoDelay = value == "true"
            case "reuse-address": cfg.reuseAddress = value == "true"
            case "backlog": cfg.backlog = Int(value)
            case "send-buffer": cfg.sendBuffer = Int(value)
            case "receive-buffer": cfg.receiveBuffer = Int(value)
            case "threads": cfg.threads = Int(value)
            case "tls-cert": cfg.tlsCert = value
            case "tls-key": cfg.tlsKey = value
            default: break
            }
        }
        cfg.applyProfile()
        return cfg
    }

    mutating func applyProfile() {
        switch profile {
        case "default":
            break
        case "tuned":
            let cpu = ProcessInfo.processInfo.processorCount
            // tcp-nodelay is managed by SwiftNIO but set for consistency
            if tcpNoDelay == nil { tcpNoDelay = true }
            if reuseAddress == nil { reuseAddress = true }
            if backlog == nil { backlog = 1024 }
            if threads == nil { threads = cpu }
        case _ where profile.hasPrefix("keel-equiv"):
            connectionClose = true
        default:
            fputs("Unknown profile: \(profile)\n", stderr)
            fputs("Available: default, tuned, keel-equiv-<version>\n", stderr)
            Foundation.exit(1)
        }
    }

    func display() {
        let cpu = ProcessInfo.processInfo.processorCount
        let osDefaults = detectOsSocketDefaults()

        func fmt(_ label: String, _ value: String) {
            // Match JVM format: "  %-22s %s"
            let padded = label.padding(toLength: 22, withPad: " ", startingAt: 0)
            print("  \(padded) \(value)")
        }

        print("=== Benchmark Configuration ===")
        fmt("server:", "swift-hello")
        fmt("port:", "\(port)")
        fmt("profile:", profile)
        fmt("cpu-cores:", "\(cpu)")
        print()

        print("--- Connection ---")
        fmt("connection-close:", "\(connectionClose)")
        print()

        print("--- Socket Options ---")
        // tcp-nodelay/send-buffer/receive-buffer: accepted via CLI but not applied
        // (SwiftNIO manages these internally)
        if let v = tcpNoDelay {
            fmt("tcp-nodelay:", "\(v) (accepted, not applied by SwiftNIO)")
        } else {
            fmt("tcp-nodelay:", "(not configurable, managed by SwiftNIO)")
        }
        if let v = reuseAddress {
            fmt("reuse-address:", "\(v)")
        } else {
            fmt("reuse-address:", "true (default by Hummingbird)")
        }
        if let v = backlog {
            fmt("backlog:", "\(v)")
        } else {
            fmt("backlog:", "256 (default by Hummingbird)")
        }
        if let v = sendBuffer {
            fmt("send-buffer:", "\(v) bytes (accepted, not applied by SwiftNIO)")
        } else {
            fmt("send-buffer:", "\(osDefaults.sendBuffer) bytes (default by OS)")
        }
        if let v = receiveBuffer {
            fmt("receive-buffer:", "\(v) bytes (accepted, not applied by SwiftNIO)")
        } else {
            fmt("receive-buffer:", "\(osDefaults.receiveBuffer) bytes (default by OS)")
        }
        if let t = threads {
            if t == cpu {
                fmt("threads:", "\(t) (tuned: cpu-cores)")
            } else {
                fmt("threads:", "\(t)")
            }
        } else {
            fmt("threads:", "\(cpu) (default by NIO, System.coreCount)")
        }
        print()

        print("--- TLS ---")
        fmt("tls:", "\(tls)")
        if tls {
            fmt("tls-cert:", tlsCert)
            fmt("tls-key:", tlsKey)
        }
        print()

        print("--- Engine-Specific (swift-hello) ---")
        fmt("(backlog/reuseAddress via Hummingbird)", "")
    }
}

// MARK: - OS Defaults

struct OsSocketDefaults {
    let sendBuffer: Int
    let receiveBuffer: Int
}

func detectOsSocketDefaults() -> OsSocketDefaults {
    #if canImport(Darwin)
    let fd = Darwin.socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
    #elseif canImport(Glibc)
    let fd = Glibc.socket(AF_INET, Int32(SOCK_STREAM.rawValue), Int32(IPPROTO_TCP))
    #endif
    guard fd >= 0 else { return OsSocketDefaults(sendBuffer: 0, receiveBuffer: 0) }
    defer {
        #if canImport(Darwin)
        Darwin.close(fd)
        #elseif canImport(Glibc)
        Glibc.close(fd)
        #endif
    }

    var sndbuf: Int32 = 0
    var rcvbuf: Int32 = 0
    var len = socklen_t(MemoryLayout<Int32>.size)

    getsockopt(fd, SOL_SOCKET, SO_SNDBUF, &sndbuf, &len)
    len = socklen_t(MemoryLayout<Int32>.size)
    getsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcvbuf, &len)

    return OsSocketDefaults(sendBuffer: Int(sndbuf), receiveBuffer: Int(rcvbuf))
}

// MARK: - WebSocket echo handler

@Sendable
func wsEchoHandler(
    inbound: WebSocketInboundStream,
    outbound: WebSocketOutboundWriter,
    _: HTTP1WebSocketUpgradeChannel.Context
) async throws {
    for try await frame in inbound {
        switch frame.opcode {
        case .text:
            try await outbound.write(.text(String(buffer: frame.data)))
        case .binary:
            try await outbound.write(.binary(frame.data))
        case .continuation:
            // Continuation frames are surfaced for non-final fragments;
            // keep echoing them as binary so the wire format round-trips.
            try await outbound.write(.binary(frame.data))
        }
    }
}

// MARK: - Connection Close Middleware

struct ConnectionCloseMiddleware<Context: RequestContext>: RouterMiddleware {
    func handle(_ request: Request, context: Context, next: (Request, Context) async throws -> Response) async throws -> Response {
        var response = try await next(request, context)
        response.headers[.connection] = "close"
        return response
    }
}

// MARK: - Main

let config = BenchConfig.parse()

if config.showConfig {
    config.display()
} else {
    let router = Router()

    if config.connectionClose {
        router.addMiddleware { ConnectionCloseMiddleware() }
    }

    router.get("/hello") { _, _ in
        Response(status: .ok, headers: [.contentType: "text/plain"], body: .init(byteBuffer: ByteBuffer(bytes: helloPayloadBytes)))
    }
    router.get("/large") { _, _ in
        Response(status: .ok, headers: [.contentType: "text/plain"], body: .init(byteBuffer: ByteBuffer(bytes: largePayloadBytes)))
    }
    // POST /upload-stream — drain Hummingbird's RequestBody async sequence
    // (no aggregation), reply with the byte count in X-Bytes-Received.
    router.post("/upload-stream") { request, _ in
        var received = 0
        for try await buffer in request.body {
            received += buffer.readableBytes
        }
        var headers = HTTPFields()
        headers[.contentType] = "text/plain"
        headers[HTTPField.Name("X-Bytes-Received")!] = "\(received)"
        return Response(status: .ok, headers: headers, body: .init(byteBuffer: ByteBuffer(bytes: uploadAckBytes)))
    }
    // GET /sse-stream?count=N&size=M — emit N SSE frames of M bytes each
    // via a streamed ResponseBody. Defaults match the rest of the engines.
    router.get("/sse-stream") { request, _ in
        let count = request.uri.queryParameters["count"].flatMap { Int($0) } ?? sseDefaultCount
        let size = request.uri.queryParameters["size"].flatMap { Int($0) } ?? sseDefaultSize
        let payload = ByteBuffer(string: "data: \(String(repeating: "x", count: size))\n\n")
        var headers = HTTPFields()
        headers[.contentType] = "text/event-stream"
        let body = ResponseBody { writer in
            for _ in 0..<count {
                try await writer.write(payload)
            }
            try await writer.finish(nil)
        }
        return Response(status: .ok, headers: headers, body: body)
    }

    let eventLoopGroup: any EventLoopGroup
    if let threads = config.threads {
        eventLoopGroup = MultiThreadedEventLoopGroup(numberOfThreads: threads)
    } else {
        eventLoopGroup = MultiThreadedEventLoopGroup.singleton
    }

    let appConfig = ApplicationConfiguration(
        address: .hostname("0.0.0.0", port: config.port),
        backlog: config.backlog ?? 256,
        reuseAddress: config.reuseAddress ?? true
    )

    // /ws-echo upgrade is wired at the server (HTTP/1.1 Upgrade) layer so
    // Hummingbird can intercept the handshake before the route dispatcher
    // runs. The handler echoes every text / binary frame back to the
    // client, mirroring the Spring / Vertx / Netty handlers and the
    // k6 ws-echo.js scenario.
    let app: Application<RouterResponder<BasicRequestContext>>
    if config.tls {
        let cert = try NIOSSLCertificate.fromPEMFile(config.tlsCert)
        let key = try NIOSSLPrivateKey(file: config.tlsKey, format: .pem)
        var tlsConfig = TLSConfiguration.makeServerConfiguration(
            certificateChain: cert.map { .certificate($0) },
            privateKey: .privateKey(key)
        )
        tlsConfig.applicationProtocols = ["http/1.1"]
        app = Application(
            router: router,
            server: try .tls(
                .http1WebSocketUpgrade { request, _, _ in
                    guard request.path == "/ws-echo" else { return .dontUpgrade }
                    return .upgrade(HTTPFields(), wsEchoHandler)
                },
                tlsConfiguration: tlsConfig
            ),
            configuration: appConfig,
            eventLoopGroupProvider: .shared(eventLoopGroup)
        )
    } else {
        app = Application(
            router: router,
            server: .http1WebSocketUpgrade { request, _, _ in
                guard request.path == "/ws-echo" else { return .dontUpgrade }
                return .upgrade(HTTPFields(), wsEchoHandler)
            },
            configuration: appConfig,
            eventLoopGroupProvider: .shared(eventLoopGroup)
        )
    }

    let scheme = config.tls ? "https" : "http"
    print("Swift Hummingbird server started on \(scheme)://0.0.0.0:\(config.port)")
    try await app.runService()
}
