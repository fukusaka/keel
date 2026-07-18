// Swift URLSession (NSURLSession) client benchmark — the Apple-native reference
// for the keel HTTP client bench. URLSession is what Ktor's Darwin engine wraps
// and the idiomatic Apple client (keep-alive pool + HTTP/2), so it is the native
// ceiling on Apple platforms (keel's macOS / iOS path via NWConnection). Accepts
// the same CLI flags as the JVM harness and prints the same result line
// (name=swift-nsurlsession). macOS-only.
//
// Line format: <name><endpoint>|<rps>|<p50ms>|<p99ms>|<p99.9ms>|<maxms>|<b/op>|<errors>
// bytes/op is n/a for native clients; percentiles are exact (sorted samples).

import Foundation

struct Config {
    var targets: [String] // full URLs (base + endpoint)
    var endpoint: String
    var connections: Int
    var warmup: Int
    var duration: Int
    var pinned: Bool
}

func parse() -> Config {
    var target = ""
    var endpoint = "/hello"
    var connections = 50
    var warmup = 3
    var duration = 10
    var pinned = false
    for arg in CommandLine.arguments.dropFirst() {
        guard arg.hasPrefix("--"), let eq = arg.firstIndex(of: "=") else { continue }
        let key = String(arg[arg.index(arg.startIndex, offsetBy: 2)..<eq])
        let val = String(arg[arg.index(after: eq)...])
        switch key {
        case "client-target": target = val
        case "client-endpoint": endpoint = val
        case "client-connections": connections = Int(val) ?? connections
        case "client-warmup": warmup = Int(val) ?? warmup
        case "client-duration": duration = Int(val) ?? duration
        case "client-target-mode": pinned = val == "pinned"
        default: break
        }
    }
    if target.isEmpty {
        FileHandle.standardError.write(Data("missing --client-target\n".utf8))
        exit(1)
    }
    let targets = target.split(separator: ",").map { part -> String in
        var s = part.trimmingCharacters(in: .whitespaces)
        while s.hasSuffix("/") { s.removeLast() }
        return s + endpoint
    }.filter { !$0.isEmpty }
    return Config(targets: targets, endpoint: endpoint, connections: connections,
                  warmup: warmup, duration: duration, pinned: pinned)
}

// Each worker owns one target (targets[worker % count]) for all its requests —
// per-worker distribution (pinned-style), which is exact for a single fixture
// and for pinned multi-host; it is not per-request round-robin.
func runPhase(session: URLSession, cfg: Config, secs: Int) async -> (rps: Double, lat: [Int64], errors: Int) {
    let deadlineNs = DispatchTime.now().uptimeNanoseconds + UInt64(secs) * 1_000_000_000
    let startNs = DispatchTime.now().uptimeNanoseconds
    let result = await withTaskGroup(of: (lat: [Int64], errors: Int, completed: Int).self) { group in
        for worker in 0..<cfg.connections {
            let url = URL(string: cfg.targets[worker % cfg.targets.count])!
            group.addTask {
                var lat: [Int64] = []
                var errors = 0
                var completed = 0
                while DispatchTime.now().uptimeNanoseconds < deadlineNs {
                    let t0 = DispatchTime.now().uptimeNanoseconds
                    do {
                        let (data, _) = try await session.data(from: url)
                        _ = data.count
                        lat.append(Int64(DispatchTime.now().uptimeNanoseconds - t0))
                        completed += 1
                    } catch {
                        errors += 1
                    }
                }
                return (lat, errors, completed)
            }
        }
        var allLat: [Int64] = []
        var totErr = 0
        var totComp = 0
        for await r in group {
            allLat.append(contentsOf: r.lat)
            totErr += r.errors
            totComp += r.completed
        }
        return (lat: allLat, errors: totErr, completed: totComp)
    }
    let elapsed = Double(DispatchTime.now().uptimeNanoseconds - startNs) / 1e9
    let sorted = result.lat.sorted()
    let rps = elapsed > 0 ? Double(result.completed) / elapsed : 0
    return (rps, sorted, result.errors)
}

func pctMs(_ sorted: [Int64], _ q: Double) -> Double {
    if sorted.isEmpty { return 0 }
    return Double(sorted[Int(q * Double(sorted.count - 1))]) / 1e6
}

let name = "swift-nsurlsession"
let cfg = parse()
let sessionConfig = URLSessionConfiguration.default
sessionConfig.httpMaximumConnectionsPerHost = cfg.connections
sessionConfig.timeoutIntervalForRequest = 30
let session = URLSession(configuration: sessionConfig)
// NOTE: URLSession caps around ~7k rps on this loopback micro-bench regardless
// of concurrency — it peaks near conns=8 and degrades beyond (conns=100 -> 20ms
// p50). Verified this is not a serial-delegate-queue artifact (a concurrent
// delegateQueue sized to the connection count changed nothing). It reflects
// URLSession / CFNetwork per-request overhead: URLSession is built for
// real-network mobile patterns (connection management, battery, caching,
// background transfer), not raw loopback throughput. Ktor's Darwin engine wraps
// URLSession and inherits this ceiling; a raw-socket Apple client (keel via
// NWConnection) can far exceed it.
FileHandle.standardError.write(Data(
    "client bench: type=\(name) targets=\(cfg.targets.count) conns=\(cfg.connections) warmup=\(cfg.warmup)s duration=\(cfg.duration)s\n".utf8))

if cfg.warmup > 0 {
    _ = await runPhase(session: session, cfg: cfg, secs: cfg.warmup)
}
let (rps, lat, errors) = await runPhase(session: session, cfg: cfg, secs: cfg.duration)
let maxMs = lat.isEmpty ? 0.0 : Double(lat[lat.count - 1]) / 1e6
print(String(format: "%@%@|%.0f|%.3f|%.3f|%.3f|%.3f|n/a|%d",
             name, cfg.endpoint, rps,
             pctMs(lat, 0.50), pctMs(lat, 0.99), pctMs(lat, 0.999), maxMs, errors))
