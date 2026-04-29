// swift-tools-version: 5.9

import PackageDescription

let package = Package(
    name: "OTelMobile",
    platforms: [.iOS(.v15), .macOS(.v13)],
    products: [
        .library(name: "OTelMobileCore", targets: ["OTelMobileCore"]),
        .library(name: "OTelMobileSDK", targets: ["OTelMobileSDK"]),
        .library(name: "LifecycleInstrumentation", targets: ["LifecycleInstrumentation"]),
        .library(name: "ScreenInstrumentation", targets: ["ScreenInstrumentation"]),
        .library(name: "NetworkInstrumentation", targets: ["NetworkInstrumentation"]),
        .library(name: "ErrorsInstrumentation", targets: ["ErrorsInstrumentation"]),
        .library(name: "VitalsInstrumentation", targets: ["VitalsInstrumentation"]),
        .library(name: "FreezeInstrumentation", targets: ["FreezeInstrumentation"]),
    ],
    dependencies: [
        .package(url: "https://github.com/open-telemetry/opentelemetry-swift.git", from: "2.1.1"),
        .package(url: "https://github.com/open-telemetry/opentelemetry-swift-core.git", from: "2.1.0"),
        .package(url: "https://github.com/apple/swift-collections.git", from: "1.1.0"),
    ],
    targets: [
        .target(
            name: "OTelMobileCore",
            dependencies: [
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ]
        ),
        .target(
            name: "OTelMobileSDK",
            dependencies: [
                "OTelMobileCore",
                "NetworkInstrumentation",
                "LifecycleInstrumentation",
                "ErrorsInstrumentation",
                "ScreenInstrumentation",
                "FreezeInstrumentation",
                "VitalsInstrumentation",
                .product(name: "OpenTelemetrySdk", package: "opentelemetry-swift-core"),
                .product(name: "OpenTelemetryProtocolExporter", package: "opentelemetry-swift"),
                .product(name: "OpenTelemetryProtocolExporterHTTP", package: "opentelemetry-swift"),
                .product(name: "DequeModule", package: "swift-collections"),
            ]
        ),
        .target(
            name: "LifecycleInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ]
        ),
        .target(
            name: "ScreenInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ]
        ),
        .target(
            name: "NetworkInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ]
        ),
        .target(
            name: "ErrorsInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ]
        ),
        .target(
            name: "VitalsInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ]
        ),
        .target(
            name: "FreezeInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ]
        ),
        .testTarget(
            name: "OTelMobileSDKTests",
            dependencies: ["OTelMobileSDK", "NetworkInstrumentation"]
        ),
        .testTarget(
            name: "ErrorsInstrumentationTests",
            dependencies: [
                "ErrorsInstrumentation",
                .product(name: "OpenTelemetrySdk", package: "opentelemetry-swift-core"),
            ]
        ),
        .testTarget(
            name: "LifecycleInstrumentationTests",
            dependencies: [
                "LifecycleInstrumentation",
                .product(name: "OpenTelemetrySdk", package: "opentelemetry-swift-core"),
            ]
        ),
    ]
)
