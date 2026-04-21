// swift-tools-version: 5.9
//
// Test-only package definition. Lets us run the React-independent bridge
// dispatcher tests (`Dash0MobileBridgeDispatcherTests`) with `swift test`
// without requiring Xcode, React Native, or a full iOS simulator.
//
// Deliberately excludes:
//   - RCTDash0MobileModule.{m,swift} — depend on React headers
//   - OTelMobileCallSink.swift — depends on OTelMobileSDK (SwiftPM package)
//
// Those bigger pieces are integration-tested via the host demo app
// (AstronomyShopRN). This package is strictly for the contract layer.

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
    targets: [
        .target(
            name: "Dash0MobileReactNative",
            path: "ios",
            exclude: [
                "RCTDash0MobileModule.m",
                "RCTDash0MobileModule.swift",
                "OTelMobileCallSink.swift",
                "Tests",
            ],
            sources: [
                "BridgeCallSink.swift",
                "Dash0MobileBridgeDispatcher.swift",
            ]
        ),
        .testTarget(
            name: "Dash0MobileReactNativeTests",
            dependencies: ["Dash0MobileReactNative"],
            path: "ios/Tests"
        ),
    ]
)
