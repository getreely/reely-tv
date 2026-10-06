// swift-tools-version:5.10
// Reely's core for iPhone, iPad and Apple TV: Plex, live TV, the provider's films and
// series, Requests. No screens here, so it builds and is tested on Linux as well as on a Mac.
import PackageDescription

let package = Package(
    name: "ReelyCore",
    platforms: [.iOS(.v17), .tvOS(.v17), .macOS(.v14)],
    products: [.library(name: "ReelyCore", targets: ["ReelyCore"])],
    targets: [
        .target(name: "ReelyCore"),
        .testTarget(name: "ReelyCoreTests", dependencies: ["ReelyCore"]),
    ]
)
