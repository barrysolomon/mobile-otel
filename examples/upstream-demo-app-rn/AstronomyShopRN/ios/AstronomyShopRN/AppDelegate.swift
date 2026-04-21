import UIKit
import React
import React_RCTAppDelegate
import ReactAppDependencyProvider
// `Dash0Mobile` is the CocoaPods pod that exposes our RN native module. It
// doesn't see OTelMobileSDK at compile time (hybrid SwiftPM+CocoaPods
// ordering), so we install the real sink from the app target here before
// RN kicks off its native-module registry.
import Dash0Mobile

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
  var window: UIWindow?

  var reactNativeDelegate: ReactNativeDelegate?
  var reactNativeFactory: RCTReactNativeFactory?

  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    // Register the Dash0Mobile native-module sink BEFORE React starts.
    // RN instantiates NativeModules lazily on first JS access, so as long
    // as we install before `factory.startReactNative` this is safe.
    // OTelMobileCallSink lives in this app target (see
    // `OTelMobileCallSink.swift` in the project navigator). It depends on
    // `OTelMobileSDK`, which is attached to this project via SwiftPM.
    NSLog("[Dash0Mobile AppDelegate] installSink { OTelMobileCallSink() }")
    Dash0MobileModule.installSink { OTelMobileCallSink() }

    let delegate = ReactNativeDelegate()
    let factory = RCTReactNativeFactory(delegate: delegate)
    delegate.dependencyProvider = RCTAppDependencyProvider()

    reactNativeDelegate = delegate
    reactNativeFactory = factory

    window = UIWindow(frame: UIScreen.main.bounds)

    factory.startReactNative(
      withModuleName: "AstronomyShopRN",
      in: window,
      launchOptions: launchOptions
    )

    return true
  }
}

class ReactNativeDelegate: RCTDefaultReactNativeFactoryDelegate {
  override func sourceURL(for bridge: RCTBridge) -> URL? {
    self.bundleURL()
  }

  override func bundleURL() -> URL? {
#if DEBUG
    RCTBundleURLProvider.sharedSettings().jsBundleURL(forBundleRoot: "index")
#else
    Bundle.main.url(forResource: "main", withExtension: "jsbundle")
#endif
  }
}
