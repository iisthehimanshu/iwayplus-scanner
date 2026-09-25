#
# To learn more about a Podspec see http://guides.cocoapods.org/syntax/podspec.html.
# Run `pod lib lint iwayplus_scanner.podspec` to validate before publishing.
#
Pod::Spec.new do |s|
  s.name             = 'iwayplus_scanner'
  s.version          = '0.0.1'
  s.summary          = 'BLE, GPS and heading scanning relayed into the Iwayplus navigation WebView.'
  s.description      = <<-DESC
Native BLE advertisement, GPS and compass scanning for a Flutter host app,
relayed into the Iwayplus navigation page running in a WebView.
                       DESC
  s.homepage         = 'https://iwayplus.in'
  s.license          = { :type => 'Proprietary', :file => '../LICENSE' }
  s.author           = { 'Iwayplus' => 'support@iwayplus.in' }
  s.source           = { :path => '.' }
  s.source_files = 'iwayplus_scanner/Sources/iwayplus_scanner/**/*'
  s.dependency 'Flutter'
  s.frameworks = 'CoreBluetooth', 'CoreLocation', 'CoreMotion'
  s.platform = :ios, '14.0'

  # Flutter.framework does not contain a i386 slice.
  s.pod_target_xcconfig = { 'DEFINES_MODULE' => 'YES', 'EXCLUDED_ARCHS[sdk=iphonesimulator*]' => 'i386' }
  s.swift_version = '5.0'

  # If your plugin requires a privacy manifest, for example if it uses any
  # required reason APIs, update the PrivacyInfo.xcprivacy file to describe your
  # plugin's privacy impact, and then uncomment this line. For more information,
  # see https://developer.apple.com/documentation/bundleresources/privacy_manifest_files
  # s.resource_bundles = {'iwayplus_scanner_privacy' => ['iwayplus_scanner/Sources/iwayplus_scanner/PrivacyInfo.xcprivacy']}
end
