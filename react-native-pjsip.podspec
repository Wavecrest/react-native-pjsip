require 'json'

package = JSON.parse(File.read(File.join(__dir__, 'package.json')))

Pod::Spec.new do |s|
  s.name         = package['name']
  s.version      = package['version']
  s.summary      = package['description']
  s.license      = package['license']

  s.authors      = package['author']
  s.homepage     = package['homepage']
  s.platform     = :ios, "15.6"

  s.source       = { :path => '.' }
  s.source_files  = "ios/RTCPjSip/**/*.{h,m}"

  s.vendored_frameworks='ios/VialerPJSIP.xcframework'
  s.xcconfig = {
    'GCC_PREPROCESSOR_DEFINITIONS' => 'PJ_AUTOCONF=1',
    'USE_HEADERMAP' => 'NO',
  }

  s.dependency 'React'
  s.dependency 'Reachability', '~> 3.2'
end
