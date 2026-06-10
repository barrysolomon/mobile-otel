// swift-tools-version: 5.9
//
// SwiftPM package for the React Native iOS bridge.
//
// Compiles + unit-tests the React-independent bridge layer WITHOUT requiring
// Xcode-resident React Native: the `Dash0MobileReactNative` target carries the
// pure-Swift contract (BridgeCallSink protocol, the bridge dispatcher, the
// bounded live-span store) AND the production `OTelMobileCallSink`, which is
// linked against the sibling `otel-ios-mobile` SwiftPM package via a local
// path dependency. That makes the sink — historically excluded and therefore
// untested, the #1 RN-iOS integration risk — a first-class compiled + tested
// citizen here.
//
// Still excluded (they need React headers, integration-tested via the host
// demo app AstronomyShopRN):
//   - RCTDash0MobileModule.{m,swift}
//
// The sink itself is guarded by `#if canImport(OTelMobileSDK)` so the source
// is harmless in build graphs where the SDK isn't linked (e.g. a CocoaPods-only
// host that copies the file into its app target); here the SDK *is* linked, so
// the guard is satisfied and the sink compiles.

import PackageDescription

let package = Package(
    name: "Dash0MobileReactNative",
    platforms: [.iOS(.v15), .macOS(.v13)],
    products: [
        .library(
            name: "Dash0MobileReactNative",
            targets: ["Dash0MobileReactNative"]
        ),
    ],
    dependencies: [
        // Local path dependency on the iOS SDK so `import OTelMobileSDK` in
        // OTelMobileCallSink.swift resolves and the sink genuinely compiles +
        // tests against the real SDK surface (MobileConfig, OTelMobile, Span,
        // Severity, SpanKind, AttributeValue, …) rather than a stand-in.
        .package(path: "../../otel-ios-mobile"),
        // OTel-Swift core is a transitive dependency of OTelMobileSDK; declare
        // it directly so the TEST target can build real Tracer/Logger/Meter
        // handles (with in-memory exporters) to inject into the sink.
        //
        // PINNED EXACT to the version otel-ios-mobile is authored against
        // (its Package.resolved → 2.4.1). A looser `from:` lets SwiftPM resolve
        // a newer core (2.5.0 made `LogRecordExporter.export` async), which
        // breaks the SDK's own source — which we must not edit. Exact-pinning
        // keeps the whole graph on the single version the SDK compiles under.
        .package(
            url: "https://github.com/open-telemetry/opentelemetry-swift-core.git",
            exact: "2.4.1"
        ),
    ],
    targets: [
        .target(
            name: "Dash0MobileReactNative",
            dependencies: [
                // Real iOS SDK: satisfies `canImport(OTelMobileSDK)` and links
                // the OTel-Swift transitive graph the sink builds spans/logs/
                // metrics through.
                .product(name: "OTelMobileSDK", package: "otel-ios-mobile"),
            ],
            path: "ios",
            exclude: [
                // React-dependent bridge wrapper — integration-tested via the
                // AstronomyShopRN host app, can't compile without RCTBridge.
                "RCTDash0MobileModule.m",
                "RCTDash0MobileModule.swift",
                "Tests",
            ],
            sources: [
                "BridgeCallSink.swift",
                "Dash0MobileBridgeDispatcher.swift",
                // O(1) bounded live-span store. Pure Swift, no OTel/React deps.
                "BoundedLiveSpanStore.swift",
                // Production sink — now compiled here against the real SDK.
                "OTelMobileCallSink.swift",
            ]
        ),
        .testTarget(
            name: "Dash0MobileReactNativeTests",
            dependencies: [
                "Dash0MobileReactNative",
                // Tests build real OTel-Swift tracer/logger/meter handles
                // (backed by in-memory exporters) to inject into the sink.
                .product(name: "OpenTelemetryApi", package: "opentelemetry-swift-core"),
                .product(name: "OpenTelemetrySdk", package: "opentelemetry-swift-core"),
            ],
            path: "ios/Tests"
        ),
    ]
)
