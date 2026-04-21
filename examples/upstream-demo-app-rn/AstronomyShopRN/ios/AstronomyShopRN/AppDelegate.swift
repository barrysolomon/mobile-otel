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
  var reactNativeDelegate: ReactNativeDelegate?
  var reactNativeFactory: RCTReactNativeFactory?
  // Stashed for SceneDelegate to hand off to the real windowScene-bound window.
  var initialLaunchOptions: [UIApplication.LaunchOptionsKey: Any]?

  func application(
    _ application: UIApplication,
    didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
  ) -> Bool {
    // Register the Dash0Mobile native-module sink BEFORE React starts.
    // RN instantiates NativeModules lazily on first JS access, so installing
    // here (before any scene connects and drives JS execution) is safe.
    NSLog("[Dash0Mobile AppDelegate] installSink { OTelMobileCallSink() }")
    Dash0MobileModule.installSink { OTelMobileCallSink() }

    let delegate = ReactNativeDelegate()
    let factory = RCTReactNativeFactory(delegate: delegate)
    delegate.dependencyProvider = RCTAppDependencyProvider()

    reactNativeDelegate = delegate
    reactNativeFactory = factory
    initialLaunchOptions = launchOptions

    return true
  }

  // MARK: - UIScene lifecycle
  // iOS 26 Simulator strictly requires scene-based lifecycle; without a
  // declared `UIApplicationSceneManifest` the window never paints (JS runs
  // fine but nothing renders — "no scenes" fault in the log).

  func application(
    _ application: UIApplication,
    configurationForConnecting connectingSceneSession: UISceneSession,
    options: UIScene.ConnectionOptions
  ) -> UISceneConfiguration {
    let config = UISceneConfiguration(name: "Default Configuration", sessionRole: connectingSceneSession.role)
    config.delegateClass = SceneDelegate.self
    return config
  }
}

@objc(SceneDelegate)
class SceneDelegate: UIResponder, UIWindowSceneDelegate {
  var window: UIWindow?

  func scene(
    _ scene: UIScene,
    willConnectTo session: UISceneSession,
    options connectionOptions: UIScene.ConnectionOptions
  ) {
    NSLog("[Dash0Mobile SceneDelegate] willConnectTo fired")
    guard let windowScene = scene as? UIWindowScene else {
      NSLog("[Dash0Mobile SceneDelegate] scene is not UIWindowScene")
      return
    }
    guard
      let appDelegate = UIApplication.shared.delegate as? AppDelegate,
      let factory = appDelegate.reactNativeFactory
    else {
      NSLog("[Dash0Mobile SceneDelegate] reactNativeFactory missing — AppDelegate didn't run?")
      return
    }

    let window = UIWindow(windowScene: windowScene)
    self.window = window

    NSLog("[Dash0Mobile SceneDelegate] startReactNative(window=\(window))")
    factory.startReactNative(
      withModuleName: "AstronomyShopRN",
      in: window,
      launchOptions: appDelegate.initialLaunchOptions
    )
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
