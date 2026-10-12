#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint livekit_background_effects_native.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'livekit_background_effects_native'
  s.version          = '0.0.1'
  s.summary          = 'A new Flutter plugin project.'
  s.description      = <<-DESC
A new Flutter plugin project.
                       DESC
  s.homepage         = 'http://example.com'
  s.license          = { :file => '../LICENSE' }
  s.author           = { 'Your Company' => 'email@example.com' }
  s.source           = { :path => '.' }
  s.source_files = 'livekit_background_effects_native/Sources/livekit_background_effects_native/**/*.swift'
  s.dependency 'Flutter'
  s.dependency 'flutter_webrtc'
  s.dependency 'WebRTC-SDK', '144.7559.09'
  s.platform = :ios, '15.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

  s.resource_bundles = {'livekit_background_effects_native_privacy' => ['livekit_background_effects_native/Sources/livekit_background_effects_native/PrivacyInfo.xcprivacy']}
end
