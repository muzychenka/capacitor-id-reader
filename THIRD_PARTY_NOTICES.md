# Third-party dependencies

Licenses of dependencies are separate from the license of this plugin. Verify the exact versions and all transitive dependencies when distributing an application.

| Dependency | Platform | Published license / source |
| --- | --- | --- |
| NFCPassportReader 2.3.x | iOS | MIT: https://github.com/AndyQ/NFCPassportReader/blob/main/LICENSE |
| OpenSSL (transitive through NFCPassportReader) | iOS | Apache-2.0: https://github.com/openssl/openssl/blob/master/LICENSE.txt |
| JMRTD 0.7.35 | Android | LGPL: https://repo.maven.apache.org/maven2/org/jmrtd/jmrtd/0.7.35/jmrtd-0.7.35.pom |
| Scuba SC Android 0.0.24 | Android | LGPL: https://repo.maven.apache.org/maven2/net/sf/scuba/scuba-sc-android/0.0.24/scuba-sc-android-0.0.24.pom |
| Bouncy Castle bcprov/bcutil 1.86 | Android | Bouncy Castle licence: https://www.bouncycastle.org/licence.html |
| Capacitor 8 | Both | MIT: https://github.com/ionic-team/capacitor/blob/main/LICENSE |
| Google ML Kit Text Recognition | Android | Google ML Kit terms: https://developers.google.com/ml-kit/terms |
| AndroidX CameraX | Android | Apache-2.0: https://developer.android.com/jetpack/androidx |

JMRTD and Scuba metadata do not state a specific LGPL version. Inspect the distributed artifacts and source to establish the applicable version and obligations before release. The Android dependency tree may contain additional components and requires a complete license and security review.
