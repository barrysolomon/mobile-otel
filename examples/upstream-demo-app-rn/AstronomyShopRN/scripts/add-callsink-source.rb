#!/usr/bin/env ruby
# Adds OTelMobileCallSink.swift to the AstronomyShopRN app target as a
# source file. This file depends on OTelMobileSDK (delivered via SwiftPM
# at the app project level) and can't live inside the CocoaPods pod.
# Idempotent — running twice is a no-op.

require 'xcodeproj'

project_path = File.expand_path('../ios/AstronomyShopRN.xcodeproj', __dir__)
project = Xcodeproj::Project.open(project_path)

target = project.targets.find { |t| t.name == 'AstronomyShopRN' } or abort "No AstronomyShopRN target"
group = project.main_group.find_subpath('AstronomyShopRN', true)

file_name = 'OTelMobileCallSink.swift'

# Already in the project?
existing = group.files.find { |f| f.path == file_name }
if existing && target.source_build_phase.files_references.include?(existing)
  puts "#{file_name} already added to target."
  exit 0
end

file_ref = existing || group.new_reference(file_name)
unless target.source_build_phase.files_references.include?(file_ref)
  target.add_file_references([file_ref])
  puts "Added #{file_name} to AstronomyShopRN source build phase."
else
  puts "#{file_name} reference exists, already in build phase."
end

project.save
puts "Saved project."
