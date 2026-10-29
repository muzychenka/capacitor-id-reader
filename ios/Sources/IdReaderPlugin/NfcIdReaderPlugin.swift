import AVFoundation
import Capacitor
import CoreNFC
import Foundation
import NFCPassportReader

@objc(NfcIdReaderPlugin)
public class NfcIdReaderPlugin: CAPPlugin, CAPBridgedPlugin {
    public let identifier = "NfcIdReaderPlugin"
    public let jsName = "NfcIdReader"
    public let pluginMethods: [CAPPluginMethod] = [
        CAPPluginMethod(name: "isAvailable", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "readDocument", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "cancel", returnType: CAPPluginReturnPromise),
        CAPPluginMethod(name: "scanMrz", returnType: CAPPluginReturnPromise)
    ]

    private let reader = PassportReader()
    private var scanning = false
    private let readLock = NSLock()
    private var reading = false

    private func finishRead() {
        readLock.lock()
        reading = false
        readLock.unlock()
    }

    @objc func isAvailable(_ call: CAPPluginCall) {
        let supported = NFCTagReaderSession.readingAvailable

        call.resolve([
            // iOS exposes no separate "NFC is switched off" state - it is either usable or not.
            "available": supported,
            "enabled": supported
        ])
    }

    @objc func readDocument(_ call: CAPPluginCall) {
        guard let documentNumber = call.getString("documentNumber"),
              let dateOfBirth = call.getString("dateOfBirth"),
              let dateOfExpiry = call.getString("dateOfExpiry"),
              documentNumber.range(of: "^[A-Z0-9<]{1,9}$", options: .regularExpression) != nil,
              dateOfBirth.range(of: "^[0-9]{6}$", options: .regularExpression) != nil,
              dateOfExpiry.range(of: "^[0-9]{6}$", options: .regularExpression) != nil else {
            call.reject("documentNumber, dateOfBirth and dateOfExpiry are required", "NFC_WRONG_KEY")
            return
        }

        guard NFCTagReaderSession.readingAvailable else {
            call.reject("This device cannot read NFC documents", "NFC_UNAVAILABLE")
            return
        }

        let mrzKey = Self.makeMrzKey(
            documentNumber: documentNumber,
            dateOfBirth: dateOfBirth,
            dateOfExpiry: dateOfExpiry
        )

        // COM lists what the chip advertises, so it is always read even when the caller only
        // wants DG1 - otherwise there is no way to discover what the document actually holds.
        let requested = call.getArray("dataGroups", String.self) ?? ["DG1"]
        guard requested.allSatisfy({ ["DG1", "DG2"].contains($0.uppercased()) }) else {
            call.reject("Only DG1 and DG2 are supported", "NFC_UNSUPPORTED_DATA_GROUP")
            return
        }
        let includeDG2 = requested.contains { $0.uppercased() == "DG2" }
        readLock.lock()
        if reading {
            readLock.unlock()
            call.reject("A read is already in progress", "NFC_BUSY")
            return
        }
        reading = true
        readLock.unlock()


        Task {
            defer { self.finishRead() }
            do {
                let passport = try await reader.readPassport(
                    mrzKey: mrzKey,
                    tags: includeDG2 ? [.DG1, .DG2] : [.DG1],
                    skipSecureElements: false
                )
                guard passport.dataGroupsRead[.DG1] != nil else {
                    call.reject("Could not read DG1", "NFC_READ_FAILED")
                    return
                }
                var result = self.toResult(passport)
                if includeDG2 {
                    guard let dg2 = passport.dataGroupsRead[.DG2] as? DataGroup2,
                          !dg2.imageData.isEmpty, dg2.imageData.count <= 8 * 1024 * 1024,
                          dg2.imageDataType == 0 || dg2.imageDataType == 1 else {
                        call.reject("Could not read a supported DG2 face image", "NFC_DG2_READ_FAILED")
                        return
                    }
                    var image = ["base64": Data(dg2.imageData).base64EncodedString(),
                                 "mimeType": dg2.imageDataType == 0 ? "image/jpeg" : "image/jp2"]
                    if dg2.imageDataType == 1, let preview = passport.passportImage?.jpegData(compressionQuality: 0.95) {
                        image["previewJpegBase64"] = preview.base64EncodedString()
                    }
                    result["faceImage"] = image
                }
                call.resolve(result)
            } catch let error as NFCPassportReaderError {
                let (message, code) = self.describe(error)
                call.reject(message, code)
            } catch {
                call.reject("Could not read the chip", "NFC_READ_FAILED")
            }
        }
    }

    @objc func scanMrz(_ call: CAPPluginCall) {
        DispatchQueue.main.async {
            guard !self.scanning,
                  let presenter = self.bridge?.viewController,
                  presenter.presentedViewController == nil else {
                call.reject("A scanner or modal is already open", "NFC_BUSY")
                return
            }
            guard Bundle.main.object(forInfoDictionaryKey: "NSCameraUsageDescription") is String else {
                call.reject("Host app must configure NSCameraUsageDescription", "CAMERA_UNAVAILABLE")
                return
            }
            guard AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back) != nil else {
                call.reject("No rear camera available", "CAMERA_UNAVAILABLE")
                return
            }
            self.scanning = true
            let present: (Bool) -> Void = { allowed in
                DispatchQueue.main.async {
                    guard allowed else {
                        self.scanning = false
                        call.reject("Camera permission denied", "CAMERA_PERMISSION_DENIED")
                        return
                    }
                    guard presenter.viewIfLoaded?.window != nil,
                          presenter.presentedViewController == nil else {
                        self.scanning = false
                        call.reject("No foreground presenter available", "CAMERA_UNAVAILABLE")
                        return
                    }
                    MrzScannerViewController.present(from: presenter, prompt: call.getString("prompt"), cancelLabel: call.getString("cancelLabel")) { lines in
                        self.scanning = false
                        guard let lines else {
                            call.reject("Scan cancelled or camera stopped", "NFC_CANCELLED")
                            return
                        }
                        call.resolve(["lines": lines])
                    }
                }
            }
            switch AVCaptureDevice.authorizationStatus(for: .video) {
            case .authorized: present(true)
            case .notDetermined: AVCaptureDevice.requestAccess(for: .video, completionHandler: present)
            default: present(false)
            }
        }
    }

    @objc func cancel(_ call: CAPPluginCall) {
        // PassportReader keeps its NFCTagReaderSession private; the user closes its system sheet.
        call.reject("Close the system NFC sheet to cancel", "NFC_CANCEL_UNSUPPORTED")
    }

    // The chip's access key is the three MRZ fields each followed by its ICAO 9303 check digit.
    private static func makeMrzKey(
        documentNumber: String,
        dateOfBirth: String,
        dateOfExpiry: String
    ) -> String {
        let paddedNumber = documentNumber.padding(toLength: 9, withPad: "<", startingAt: 0)

        return paddedNumber + checkDigit(paddedNumber)
            + dateOfBirth + checkDigit(dateOfBirth)
            + dateOfExpiry + checkDigit(dateOfExpiry)
    }

    static func checkDigit(_ value: String) -> String {
        let weights = [7, 3, 1]
        var sum = 0

        for (index, character) in value.uppercased().enumerated() {
            let digit: Int

            if let number = character.wholeNumberValue, character.isNumber {
                digit = number
            } else if character == "<" {
                digit = 0
            } else if let ascii = character.asciiValue, character.isLetter {
                digit = Int(ascii - 65) + 10
            } else {
                digit = 0
            }

            sum += digit * weights[index % 3]
        }

        return String(sum % 10)
    }

    private func toResult(_ passport: NFCPassportModel) -> [String: Any] {
        [
            "documentCode": passport.documentType,
            "documentNumber": passport.documentNumber,
            "issuingState": passport.issuingAuthority,
            "nationality": passport.nationality,
            "primaryIdentifier": passport.lastName,
            "secondaryIdentifier": passport.firstName,
            "dateOfBirth": passport.dateOfBirth,
            "dateOfExpiry": passport.documentExpiryDate,
            "gender": passport.gender,
            "personalNumber": passport.personalNumber ?? "",
            "availableDataGroups": passport.dataGroupsAvailable.map { "\($0)" },
            "readDataGroups": passport.dataGroupsRead.keys.map { "\($0)" },
            "authenticityVerified": false
        ]
    }

    private func describe(_ error: NFCPassportReaderError) -> (String, String) {
        switch error {
        case .UserCanceled:
            return ("The read was cancelled", "NFC_CANCELLED")
        case .InvalidMRZKey:
            return (
                "The document number, date of birth or expiry date does not match the chip",
                "NFC_WRONG_KEY"
            )
        case .ConnectionError, .NFCNotSupported:
            return ("This device cannot read NFC documents", "NFC_UNAVAILABLE")
        case .TagNotValid, .ResponseError:
            return ("The document was moved away before the read finished", "NFC_TAG_LOST")
        default:
            return ("Could not read the chip", "NFC_READ_FAILED")
        }
    }
}
