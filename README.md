# Capacitor ID Reader

Read the MRZ from a document camera scan and access the DG1 data group and optionally the DG2 face image of an ICAO-compatible electronic passport or identity document over NFC. The plugin works with Capacitor 8 and does not depend on a particular UI framework or identity-verification provider.

> **Compatibility:** DG2 face-image reading has been tested on physical iOS and Android devices. Camera scanning and NFC reading have also been tested on both platforms. Document and device compatibility varies; test the exact version with the documents your application supports. Android build and regression checks for the latest audit changes are still outstanding. See `AUDIT.md` for the audit and remaining limitations.

**Reading a chip is not identity verification.** This plugin does not validate document authenticity, certificate trust, the holder's identity or liveness. `authenticityVerified` is always `false`.

## Requirements

- Capacitor 8 and Node.js 22 or newer for the Capacitor toolchain.
- iOS 15 or newer, or Android API 26 or newer.
- A physical phone with NFC and an ICAO-compatible electronic document. The iOS simulator cannot read NFC chips.
- MRZ access data: document number, date of birth and expiry date. PACE-CAN and extended document numbers are not supported.

## Install

```sh
pnpm add capacitor-id-reader
pnpm exec cap sync
```

Complete the native setup below before running the application.

## Quick start

```ts
import { NfcIdReader, parseMrz } from 'capacitor-id-reader'

const { available, enabled } = await NfcIdReader.isAvailable()
if (!available || !enabled) throw new Error('NFC is unavailable or disabled')

// Opens the native camera screen. Text can be localized by the host app.
const { lines } = await NfcIdReader.scanMrz({
    prompt: 'Point the camera at the MRZ on your document',
    cancelLabel: 'Cancel'
})
const key = parseMrz(lines)
if (!key) throw new Error('Could not read the MRZ; please scan again')

// Opens the NFC session. Keep the document close to the phone.
const document = await NfcIdReader.readDocument(key)
// Use document fields in your UI; do not log or persist them unnecessarily.
```

The host app owns the surrounding UI, translations, error handling and data retention. The MRZ scanner itself is a built-in native screen, and iOS NFC uses Apple's system sheet.

## API

| Method              | Description                                                                                                                                      |
| ------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| `isAvailable()`     | Returns `{ available, enabled }`. On iOS both values reflect system NFC availability; iOS does not expose a separate NFC toggle.                 |
| `scanMrz(options?)` | Opens the native camera scanner and returns `{ lines }`. Optional `prompt` and `cancelLabel` customize its text.                                 |
| `parseMrz(lines)`   | Parses TD1, TD2 and TD3 MRZ strings, checks access-field check digits and returns the key or `undefined`. This does not authenticate a document. |
| `readDocument(key)` | Reads DG1 and optionally DG2 using MRZ-derived access data and BAC or PACE where supported.                                                      |
| `cancel()`          | Cancels an Android NFC read. On iOS the user dismisses the NFC sheet; this method rejects with `NFC_CANCEL_UNSUPPORTED`.                         |

`readDocument` accepts `{ documentNumber, dateOfBirth, dateOfExpiry }`. Dates are six digits in MRZ `YYMMDD` format. It returns document code and number, issuing state, nationality, names, dates, gender and, when available, personal number and data-group information. Availability and formatting can vary by document and platform.

Methods reject on errors. Use the exported `ENfcIdReaderError` codes for cases such as cancellation, unavailable hardware, denied camera permission and an ongoing read. A failed chip read is not guaranteed to identify the precise cause; offer a retry and check the document and MRZ input. DG1 is always read. DG2 is opt-in; other data groups are not supported.

Choose the data to read for each session:

```ts
// DG1 only (the default).
const document = await NfcIdReader.readDocument(key)

// DG1 and the DG2 face image.
const documentWithPhoto = await NfcIdReader.readDocument({
    ...key,
    dataGroups: ['DG1', 'DG2']
})
```

Each call starts a separate NFC session. Even when `dataGroups` contains only `DG2`, the plugin also reads DG1; reading only DG2 is not currently supported.

## Optional DG2 face image

DG2 can contain a biometric face image. It is read only when explicitly requested:

```ts
const document = await NfcIdReader.readDocument({ ...key, dataGroups: ['DG1', 'DG2'] })
const image = document.faceImage
if (image) {
    const jpeg =
        image.previewJpegBase64 ?? (image.mimeType === 'image/jpeg' ? image.base64 : undefined)
    if (jpeg) {
        const url = `data:image/jpeg;base64,${jpeg}`
        // Display or process the image locally according to your privacy policy.
    }
}
```

`faceImage` contains original image bytes encoded as base64 and a MIME type of `image/jpeg` or `image/jp2`. JPEG 2000 (`image/jp2`) may require a decoder and is not universally supported by browsers. On iOS, when native decoding succeeds, `previewJpegBase64` contains a JPEG preview; the original image remains in `base64`. If DG2 was requested but cannot be read or the image is missing, unsupported or larger than 8 MiB, the call rejects with `NFC_DG2_READ_FAILED`. Reading DG2 takes longer and requires keeping the phone against the document. DG2 reading does not prove authenticity or identity. Face images are sensitive biometric data: request them only when necessary and do not log them.

## iOS setup

1. Enable **Near Field Communication Tag Reading** for the application target and ensure the signing profile includes the capability.
2. Add `TAG` to the `com.apple.developer.nfc.readersession.formats` entitlement.
3. Add `NFCReaderUsageDescription` to `Info.plist` and add `A0000002471001` to the `com.apple.developer.nfc.readersession.iso7816.select-identifiers` array.
4. Add `NSCameraUsageDescription` if using `scanMrz()`.

The plugin supports Swift Package Manager and CocoaPods. The CocoaPods dependency resolves `NFCPassportReader ~> 2.3.1`.

## Android setup

The plugin declares NFC and camera permissions and marks the hardware features optional. NFC must be enabled on the device. The camera permission is requested when scanning the MRZ. NFC reading requires a foreground activity and is cancelled when it pauses. Android uses CameraX and Google ML Kit Text Recognition.

## Privacy and security

The plugin's own code does not upload or persist document images or fields. Google ML Kit and other dependencies have their own terms and disclosures. Review the host application's bridge and native debug logging; avoid recording MRZ, access keys, document data and images. Implement the retention, access controls and authenticity checks required by your application.

Reading and compatibility are not guaranteed for every country, chip or document. iOS and Android can differ in PACE/BAC fallback behavior. Review `AUDIT.md` before production use.

## Development and licensing

Run `pnpm install` and `pnpm test` in the project checkout. The `example` directory contains a minimal device test app and is not included in the npm package. NFC and camera behavior should be tested on physical devices with representative documents.

The plugin is MIT licensed. Android dependencies include JMRTD and Scuba, whose published metadata identifies LGPL; downstream distributors must review their obligations. See `LICENSE`, `THIRD_PARTY_NOTICES.md` and `AUDIT.md` in the package.
