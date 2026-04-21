#!/usr/bin/env ruby
# Adds a local Swift Package reference on ../../../otel-ios-mobile to the
# AstronomyShopRN Xcode project and links OTelMobileSDK into the main app
# target. Idempotent — running twice is a no-op.
#
# Run with: ruby scripts/add-otel-swiftpm.rb

require 'xcodeproj'
require 'pathname'

project_path = File.expand_path('../ios/AstronomyShopRN.xcodeproj', __dir__)
project = Xcodeproj::Project.open(project_path)

app_target = project.targets.find { |t| t.name == 'AstronomyShopRN' }
unless app_target
  abort("Could not find 'AstronomyShopRN' target")
end

# Xcode stores local Swift packages with a path relative to the .xcodeproj.
# xcodeproj-to-SDK is `../../../../otel-ios-mobile` (out of
# ios/AstronomyShopRN.xcodeproj → ios → AstronomyShopRN → upstream-demo-app-rn
# → examples → mobile-otel → otel-ios-mobile).
sdk_rel_path = '../../../../otel-ios-mobile'
sdk_abs_path = File.expand_path(sdk_rel_path, File.dirname(project_path))
unless File.directory?(sdk_abs_path)
  abort("SDK path does not exist (resolved from #{sdk_rel_path}): #{sdk_abs_path}")
end

product_name = 'OTelMobileSDK'

# ── 1. Ensure a local Swift Package reference exists ────────────────────────
# The `xcodeproj` gem's Object class exposes both `path` and `relativePath`
# setters via attribute methods. Xcode expects `relativePath`, not `path`.
local_pkg_ref = project.root_object.package_references.find do |ref|
  ref.isa == 'XCLocalSwiftPackageReference' &&
    (ref.respond_to?(:relative_path) ? ref.relative_path == sdk_rel_path : false)
end

unless local_pkg_ref
  # Remove any prior misconfigured refs we may have added.
  project.root_object.package_references.reject! do |ref|
    ref.isa == 'XCLocalSwiftPackageReference'
  end

  local_pkg_ref = project.new(Xcodeproj::Project::Object::XCLocalSwiftPackageReference)
  # Set the correct attribute. xcodeproj gem 1.27 maps `relative_path` → `relativePath`.
  if local_pkg_ref.respond_to?(:relative_path=)
    local_pkg_ref.relative_path = sdk_rel_path
  else
    # Fall back to writing the raw plist field
    local_pkg_ref.instance_variable_get(:@simple_attributes_hash).tap do |h|
      h ||= {}
    end
    local_pkg_ref.send(:simple_attributes_hash)['relativePath'] = sdk_rel_path
  end
  project.root_object.package_references << local_pkg_ref
  puts "Added local Swift package (relativePath=#{sdk_rel_path})"
else
  puts "Local Swift package already registered."
end

# ── 2. Link OTelMobileSDK product into the app target ───────────────────────
existing_product = app_target.package_product_dependencies.find do |dep|
  dep.product_name == product_name
end

unless existing_product
  prod_dep = project.new(Xcodeproj::Project::Object::XCSwiftPackageProductDependency)
  prod_dep.product_name = product_name
  prod_dep.package = local_pkg_ref
  app_target.package_product_dependencies << prod_dep

  # Add a build file referring to this product dependency in the
  # "Frameworks" build phase so Xcode actually links it.
  frameworks_phase = app_target.frameworks_build_phase
  build_file = project.new(Xcodeproj::Project::Object::PBXBuildFile)
  build_file.product_ref = prod_dep
  frameworks_phase.files << build_file
  puts "Linked #{product_name} into AstronomyShopRN target."
else
  puts "#{product_name} already linked."
end

project.save
puts "Saved project."
