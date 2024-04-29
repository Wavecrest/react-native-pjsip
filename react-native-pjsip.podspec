require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name         = package['name']
  s.version      = package['version']
  s.summary      = package['description']
  s.license      = package['license']

  s.authors      = package['author']
  s.homepage     = package['homepage']
  s.platform     = :ios, "14.0"

  s.source       = { :git => "https://github.com/Wavecrest/react-native-pjsip.git" }
  s.source_files  = "ios/RTCPjSip/**/*.{h,m}"

  s.dependency = 'Vialer-pjsip-iOS'
  s.vendored_frameworks='ios/local/VialerPJSIP.framework'
  s.xcconfig = {
    'GCC_PREPROCESSOR_DEFINITIONS' => 'PJ_AUTOCONF=1',
    'USE_HEADERMAP' => 'NO',
  }
  s.dependency 'React'
end


