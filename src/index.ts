import { registerPlugin } from '@capacitor/core'
export { parseMrz, getMrzCheckDigit } from './parse-mrz.js'

export enum ENfcIdReaderError {
    MISSING_FIELDS = 'NFC_MISSING_FIELDS',
    UNAVAILABLE = 'NFC_UNAVAILABLE',
    DISABLED = 'NFC_DISABLED',
    CANCELLED = 'NFC_CANCELLED',
    WRONG_KEY = 'NFC_WRONG_KEY',
    TAG_LOST = 'NFC_TAG_LOST',
    READ_FAILED = 'NFC_READ_FAILED',
    BUSY = 'NFC_BUSY',
    CANCEL_UNSUPPORTED = 'NFC_CANCEL_UNSUPPORTED',
    MRZ_UNAVAILABLE = 'MRZ_UNAVAILABLE',
    CAMERA_PERMISSION_DENIED = 'CAMERA_PERMISSION_DENIED',
    CAMERA_UNAVAILABLE = 'CAMERA_UNAVAILABLE',
    MRZ_OCR_FAILED = 'MRZ_OCR_FAILED',
    UNSUPPORTED_DATA_GROUP = 'NFC_UNSUPPORTED_DATA_GROUP',
    DG2_READ_FAILED = 'NFC_DG2_READ_FAILED'
}

// The chip derives its access key from these three MRZ fields, so they must be known
// before a read can start. Dates are YYMMDD, exactly as printed in the MRZ.
export interface INfcIdReaderKey {
    documentNumber: string
    dateOfBirth: string
    dateOfExpiry: string
}

export interface INfcIdDocument {
    documentCode: string
    documentNumber: string
    issuingState: string
    nationality: string
    primaryIdentifier: string
    secondaryIdentifier: string
    dateOfBirth: string
    dateOfExpiry: string
    gender: string
    personalNumber?: string
    availableDataGroups?: string[]
    readDataGroups?: string[]
    /** Present only when DG2 was explicitly requested and read. Bytes are base64 encoded. */
    faceImage?: { base64: string; mimeType: 'image/jpeg' | 'image/jp2'; previewJpegBase64?: string }
    /** Reading data groups does not verify the document's authenticity. */
    authenticityVerified: false
}

export interface INfcIdReaderAvailability {
    available: boolean
    enabled: boolean
}

export interface IMrzScanResult {
    lines: string[]
}

export interface IMrzScanOptions {
    prompt?: string
    cancelLabel?: string
}

export interface INfcIdReaderPlugin {
    isAvailable(): Promise<INfcIdReaderAvailability>
    /** Opens the native MRZ camera scanner. */
    scanMrz(options?: IMrzScanOptions): Promise<IMrzScanResult>
    readDocument(
        options: INfcIdReaderKey & { dataGroups?: ('DG1' | 'DG2')[] }
    ): Promise<INfcIdDocument>
    /** Android: cancel pending read. iOS: close the system NFC sheet instead. */
    cancel(): Promise<void>
}

export const NfcIdReader = registerPlugin<INfcIdReaderPlugin>('NfcIdReader')
