// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "swift-hello",
    platforms: [.macOS(.v14)],
    dependencies: [
        .package(url: "https://github.com/hummingbird-project/hummingbird.git", from: "2.0.0"),
    ],
    targets: [
        .executableTarget(
            name: "swift-hello",
            dependencies: [
                .product(name: "Hummingbird", package: "hummingbird"),
            ],
            path: "Sources"
        ),
    ]
)
