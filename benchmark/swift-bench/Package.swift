// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "swift-bench",
    platforms: [.macOS(.v14)],
    dependencies: [
        .package(url: "https://github.com/hummingbird-project/hummingbird.git", from: "2.0.0"),
        .package(url: "https://github.com/hummingbird-project/hummingbird-websocket.git", from: "2.0.0"),
        .package(url: "https://github.com/ordo-one/package-histogram", from: "0.1.2"),
    ],
    targets: [
        .executableTarget(
            name: "swift-bench",
            dependencies: [
                .product(name: "Hummingbird", package: "hummingbird"),
                .product(name: "HummingbirdTLS", package: "hummingbird"),
                .product(name: "HummingbirdWebSocket", package: "hummingbird-websocket"),
                .product(name: "HummingbirdWSCompression", package: "hummingbird-websocket"),
            ],
            path: "Sources",
            exclude: ["client"]
        ),
        // Client benchmark (URLSession / NSURLSession). Depends only on the
        // HdrHistogram port for comparable latency percentiles.
        .executableTarget(
            name: "swift-client",
            dependencies: [
                .product(name: "Histogram", package: "package-histogram"),
            ],
            path: "Sources/client"
        ),
    ]
)
