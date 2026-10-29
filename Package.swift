// swift-tools-version: 5.9
import PackageDescription
let package = Package(
    name: "CapacitorIdReader",
    platforms: [.iOS(.v15)],
    products: [.library(name: "CapacitorIdReader", targets: ["IdReaderPlugin"])],
    dependencies: [
        .package(url: "https://github.com/ionic-team/capacitor-swift-pm.git", from: "8.0.0"),
        .package(url: "https://github.com/AndyQ/NFCPassportReader.git", .upToNextMinor(from: "2.3.1"))
    ],
    targets: [.target(name: "IdReaderPlugin", dependencies: [
        .product(name: "Capacitor", package: "capacitor-swift-pm"),
        .product(name: "Cordova", package: "capacitor-swift-pm"),
        .product(name: "NFCPassportReader", package: "NFCPassportReader")
    ], path: "ios/Sources/IdReaderPlugin"),
    .testTarget(name: "IdReaderPluginTests", dependencies: ["IdReaderPlugin"], path: "ios/Tests/IdReaderPluginTests")]
)
