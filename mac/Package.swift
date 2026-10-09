// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "KodiScreencast",
    platforms: [.macOS(.v14)],
    targets: [
        .target(name: "ScreencastCore", path: "Sources/ScreencastCore"),
        .executableTarget(
            name: "KodiScreencast",
            dependencies: ["ScreencastCore"],
            path: "Sources/KodiScreencast"
        ),
        .testTarget(
            name: "ScreencastCoreTests",
            dependencies: ["ScreencastCore"],
            path: "Tests/ScreencastCoreTests"
        ),
    ]
)
