Pod::Spec.new do |s|
  s.name = 'IdReader'
  s.version = '0.2.2'
  s.summary = 'Capacitor NFC reader for ICAO identity documents'
  s.homepage = 'https://github.com/muzychenka/capacitor-id-reader'
  s.license = { :type => 'MIT', :file => 'LICENSE' }
  s.author = 'IdReader contributors'
  s.source = { :git => "https://github.com/muzychenka/capacitor-id-reader.git", :tag => "v#{s.version}" }
  s.source_files = 'ios/Sources/IdReaderPlugin/**/*.{swift,h,m}'
  s.ios.deployment_target = '15.0'
  s.swift_version = '5.0'
  s.dependency 'Capacitor'
  s.dependency 'NFCPassportReader', '~> 2.3.1'
end
