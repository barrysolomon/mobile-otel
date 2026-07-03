// swift-tools-version:5.9

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
        .library(name: "ScreenshotInstrumentation", targets: ["ScreenshotInstrumentation"]),
        .library(name: "WireframeInstrumentation", targets: ["WireframeInstrumentation"]),
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
            ],
            path: "otel-ios-mobile/Sources/OTelMobileCore",
            resources: [
                // Apple privacy manifest — must ship inside the target's
                // resource bundle so App Review's scanner can find it.
                .copy("PrivacyInfo.xcprivacy")
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
                "ScreenshotInstrumentation",
                "WireframeInstrumentation",
                .product(name: "OpenTelemetrySdk", package: "opentelemetry-swift-core"),
                .product(name: "OpenTelemetryProtocolExporter", package: "opentelemetry-swift"),
                .product(name: "OpenTelemetryProtocolExporterHTTP", package: "opentelemetry-swift"),
                .product(name: "DequeModule", package: "swift-collections"),
            ],
            path: "otel-ios-mobile/Sources/OTelMobileSDK",
            resources: [
                // Apple privacy manifest — must ship inside the target's
                // resource bundle so App Review's scanner can find it.
                .copy("PrivacyInfo.xcprivacy")
            ]
        ),
        .target(
            name: "LifecycleInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ],
            path: "otel-ios-mobile/Sources/LifecycleInstrumentation"
        ),
        .target(
            name: "ScreenInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ],
            path: "otel-ios-mobile/Sources/ScreenInstrumentation"
        ),
        .target(
            name: "NetworkInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ],
            path: "otel-ios-mobile/Sources/NetworkInstrumentation"
        ),
        .target(
            name: "ErrorsInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ],
            path: "otel-ios-mobile/Sources/ErrorsInstrumentation"
        ),
        .target(
            name: "VitalsInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ],
            path: "otel-ios-mobile/Sources/VitalsInstrumentation"
        ),
        .target(
            name: "FreezeInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ],
            path: "otel-ios-mobile/Sources/FreezeInstrumentation"
        ),
        .target(
            name: "ScreenshotInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ],
            path: "otel-ios-mobile/Sources/ScreenshotInstrumentation"
        ),
        .target(
            name: "WireframeInstrumentation",
            dependencies: [
                "OTelMobileCore",
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
            ],
            path: "otel-ios-mobile/Sources/WireframeInstrumentation"
        ),
        .testTarget(
            name: "OTelMobileSDKTests",
            dependencies: ["OTelMobileSDK", "NetworkInstrumentation"],
            path: "otel-ios-mobile/Tests/OTelMobileSDKTests"
        ),
        .testTarget(
            name: "ErrorsInstrumentationTests",
            dependencies: [
                "ErrorsInstrumentation",
                .product(name: "OpenTelemetrySdk", package: "opentelemetry-swift-core"),
            ],
            path: "otel-ios-mobile/Tests/ErrorsInstrumentationTests"
        ),
        .testTarget(
            name: "LifecycleInstrumentationTests",
            dependencies: [
                "LifecycleInstrumentation",
                .product(name: "OpenTelemetrySdk", package: "opentelemetry-swift-core"),
            ],
            path: "otel-ios-mobile/Tests/LifecycleInstrumentationTests"
        ),
        .testTarget(
            name: "ScreenshotInstrumentationTests",
            dependencies: [
                "ScreenshotInstrumentation",
                "OTelMobileCore",
                .product(name: "OpenTelemetrySdk", package: "opentelemetry-swift-core"),
            ],
            path: "otel-ios-mobile/Tests/ScreenshotInstrumentationTests"
        ),
        .testTarget(
            name: "WireframeInstrumentationTests",
            dependencies: [
                "WireframeInstrumentation",
                "OTelMobileCore",
                .product(name: "OpenTelemetrySdk", package: "opentelemetry-swift-core"),
            ],
            path: "otel-ios-mobile/Tests/WireframeInstrumentationTests"
        ),
    ]
)
