require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "NumericText"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => min_ios_version_supported }
  # The renderer is SwiftUI (ios/NumericTextSwiftUIHost.swift); the Fabric view is ObjC++ and
  # reaches it through the generated NumericText-Swift.h.
  s.swift_version = "5.0"
  s.source       = { :git => "https://github.com/AmatoGiulio/react-native-numeric-text.git", :tag => "v#{s.version}" }

  s.source_files = "ios/**/*.{h,m,mm,swift,cpp}"
  s.private_header_files = "ios/**/*.h"

  install_modules_dependencies(s)
end
