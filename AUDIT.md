# Audit — 2026-10-28

Historical audit of the 0.1.0 development revision. The subsequent 0.2.0-beta.0 DG2 implementation has not been covered by this audit and requires separate review and physical-device testing.

## Conclusion

This is an engineering review and automated dependency check, not an independent security certification. Do not promote this revision as production-validated yet. Earlier device tests do not validate the new camera, cancellation or cryptographic changes. No npm publication was performed.

## Scope

Reviewed all first-party TypeScript, Swift and Java plugin sources, native manifests, Swift and CocoaPods packaging, Gradle dependencies, tests, CI, npm metadata and example application. Checked the published-file allowlist and generated npm archive. Examined resolved NFCPassportReader logging and OpenSSL versions. This was not a line-by-line audit of every third-party dependency or a fuzzing campaign.

## Findings and changes

| Priority | Finding | Action / validation |
| --- | --- | --- |
| High, dependency exposure | JMRTD 0.7.35 requests Bouncy Castle 1.72. OSV reports nine advisories for bcprov 1.72. Reachability of each advisory through this plugin has NOT been established. | Added direct bcprov/bcutil 1.86 dependencies, which override 1.72 under normal Gradle conflict resolution. OSV returned no advisories for 1.86. Removed unused Spongy Castle dependency. Full Gradle resolution and device regression still required. |
| Medium | Android cancellation rejected the JS promise but did not close the active NFC transport. A subsequent request could overlap a still-running read. | Close active IsoDep on cancellation, retain busy state until worker cleanup, bind callbacks to the original call, and close both card service and transport. Partial Java compilation passed; cancellation/device lifecycle tests remain required. |
| Medium | iOS permission denial was reported as cancellation; requesting camera without host usage description could terminate the app. | Check host usage description, hardware availability and camera authorization before presenting. Return CAMERA_PERMISSION_DENIED for denial. |
| Medium | Repeated scanner calls could present conflicting controllers/activities and leave promises unresolved. | Added scanner guards and handling for presentation/start failures. Requires native UI regression tests. |
| Medium | Swift scanner completion state was accessed from UI and camera queues without synchronization. | Protected completion with NSLock. Build and MRZ tests pass; no Thread Sanitizer/device run performed. |
| Medium | Swift/TS MRZ parsers accepted empty document numbers and nonnumeric date fields when their check digits happened to match. | Added field-shape and header checks, plus regression fixtures. Dates remain MRZ YYMMDD strings, not full calendar/authenticity validation. |
| Medium | Android cancel could race an OCR result, and a synchronous OCR startup exception could leave an image proxy unclosed. | Mark activity finished at finish(), close proxy on OCR startup failure. Partial compilation passed. |
| Low | TypeScript declaration referenced an extensionless ESM module, incompatible with NodeNext resolution. | Use ./index.js; NodeNext type checking passes. |
| Low / development only | Example's Capacitor CLI → xcode → uuid 7.0.3 matched a moderate advisory. | Override xcode's uuid to 11.1.1. The consumer uses v4, whereas the reported issue concerns buffer APIs in other UUID versions. Example build and cap sync pass; npm audit reports no known vulnerabilities. |
| Low | iOS forwarded arbitrary native localized error details to JavaScript and retained an unused tracing delegate. | Return a generic failure message and remove unused trace state. |

Bouncy Castle advisory IDs found for 1.72:
GHSA-4h8f-2wvx-gg5w, GHSA-574f-3g2m-x479, GHSA-67mf-3cr5-8w23,
GHSA-8xfc-gm6g-vgpv, GHSA-9pwp-9qqc-pr26, GHSA-hr8g-6v94-x4m9,
GHSA-qp49-qgx5-5m26, GHSA-v435-xc8x-wvr9, GHSA-wjxj-5m7g-mg7q.
Sources: https://api.osv.dev and https://repo.maven.apache.org/maven2/org/jmrtd/jmrtd/0.7.35/jmrtd-0.7.35.pom . Advisory severity is not proof of exploitability in this plugin.

## Verification performed

- TypeScript build, NodeNext no-emit check and six MRZ unit tests passed.
- Swift simulator build and four XCTest MRZ tests passed.
- Android MRZ fixture program passed.
- Android Java source compilation passed against Android/CameraX/ML Kit/JMRTD and updated Bouncy Castle jars. Capacitor bridge signatures were stubbed: this is NOT a complete Android Gradle build, manifest merge, R8 check or integration test.
- Example Vite build and Capacitor iOS sync passed after dependency override.
- npm audit for the root previously returned zero advisories; repeated example audit returned zero after the fix.
- OSV queries for direct Android dependencies and selected crypto transitive dependencies were performed. NFCPassportReader resolved commit 6e37f1ab249fef82771da46d32707f2b94ed090f and OpenSSL 3.6.5 (OSS-Fuzz ecosystem) queries returned no matches. Empty results do not establish complete vulnerability coverage.
- Secret-pattern review found no credentials or real document records in first-party publishable sources. MRZ fixtures are ICAO specimen examples. Generated device provisioning artifacts, user-specific Xcode state, example app and node_modules are excluded by the npm files allowlist.

## Remaining release gates / limitations

1. Build the actual Android library and a host application with Android SDK 36, JDK 21 and Gradle 8.14.3. Inspect dependency resolution to confirm no host constraint downgrades Bouncy Castle. Test release/R8 packaging. No local Android SDK was available during this audit.
2. Repeat on-device MRZ/NFC tests after these changes: camera permission denied/restricted, second scan call, cancel while OCR completes, NFC cancellation during I/O, immediate retry, tag removal, wrong MRZ, background/resume, and activity recreation. Include BAC and PACE documents and multiple countries/document formats. Prior happy-path device tests were user-confirmed on both platforms; iOS used iPhone 15 Pro Max.
3. Scanner configuration/runtime camera failures on iOS can still map to NFC_CANCELLED; there is no scan timeout. Android waits for a tag until cancelled or backgrounded. iOS cancellation remains user-driven through the system sheet.
4. PACE fallback behavior differs: iOS upstream can fall back to BAC after PACE failure; Android falls back on absent/unreadable CardAccess but does not fall back after attempted PACE. Extended document numbers and CAN-only documents are unsupported. Malformed-chip fuzzing and protocol downgrade assessment were not completed.
5. Reading DG1 is not authenticity, ownership or liveness verification. No trust-anchor or passive authentication guarantee is provided; authenticityVerified stays false. Do not use a successful read alone as authorization or verified KYC.
6. NFCPassportReader contains OSLog debug statements involving keys/APDUs. Swift dynamic string interpolation is private by default, but host/debug logging configuration can alter exposure. Capacitor host logging must also be reviewed. First-party plugin code has no document upload or persistence; this does not certify third-party telemetry or device logs. Do not test sensitive debug logging with real documents.
7. Review LGPL obligations for JMRTD/Scuba and complete transitive license attribution for a distributed application. This audit is not a legal opinion. CocoaPods spec passed quick validation earlier; full CocoaPods integration and its separately resolved OpenSSL binary were not rebuilt here.
8. The example displays identity data in memory; host applications must implement their own data minimization, screen privacy, lifecycle cleanup and retention rules. A compromised host WebView can access the plugin API by design.

The 0.1.0 version was published after this audit. Rebuild and re-test the exact tarball for each subsequent release; only a beta tag is appropriate while these gates remain open.


## Follow-up review — current checkout

- Added a shared MRZ fixture set consumed by TypeScript, Swift and Java, including valid TD1/TD3, OCR substitutions, corrupted check digits, an empty document number, a bad header and an unsupported extended number. This is a regression suite, not proof of coverage for every document.
- iOS now rejects a result if DG1 was not read. Android closes the NFC transport before resolving a completed read and can report available groups when COM can be read. These changes need device regression tests, including cancellation, tag loss and immediate retry. Cancellation can return before a blocked Android read has fully unwound; a new request may temporarily return NFC_BUSY.
- DG2 still crosses the bridge as Base64 and may cause multiple copies in memory, including an iOS JPEG preview. The image size check is not a bound on the upstream parser's allocations. A secure temporary-file API and cleanup contract remain unimplemented.
- PACE/BAC fallback and some NFC error classification remain platform-specific. No claim of security equivalence is made. Neither passive authentication nor trust validation is implemented.
- GitHub Actions run 40829360541 reported that jobs did not start because of failed account payments or a spending limit. Account billing must be resolved before CI can run; this is not a compilation result.
- The changed Android NFC Java source compiles against Android API and JMRTD/Scuba libraries using stubbed Capacitor interfaces. This is not an Android Gradle build or a real-device NFC test.
- This follow-up does not constitute an independent security audit or a complete production validation.
