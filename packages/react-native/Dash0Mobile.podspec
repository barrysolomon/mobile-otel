require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name         = "Dash0Mobile"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["repository"]
  s.license      = "Apache-2.0"
  s.author       = { "Dash0" => "hello@dash0.com" }

  s.platforms    = { :ios => "15.0" }
  s.source       = { :path => "." }
  s.source_files = "ios/**/*.{h,m,mm,swift}"
  # OTelMobileCallSink.swift is excluded from the pod because it depends on
  # OTelMobileSDK, which in most RN apps is delivered via SwiftPM on the
  # app's Xcode project — not through CocoaPods. The pod compiles without
  # it; consumers copy OTelMobileCallSink.swift into their app target and
  # call `Dash0MobileModule.installSink { OTelMobileCallSink() }` from
  # AppDelegate to activate real telemetry.
  s.exclude_files = [
    "ios/Tests/**/*",
    "ios/OTelMobileCallSink.swift",
  ]
  s.swift_version = "5.9"

  s.dependency "React-Core"
end
