import type { INfcIdReaderKey } from './index.js'

const MRZ_LINE_LENGTH_TOLERANCE = 2

const MRZ_LINE_LENGTHS = {
    TD1: 30,
    TD2: 36,
    TD3: 44
} as const

// OCR reliably confuses these with digits, and every field we read is numeric except the
// document number, so the confusion can be undone before the check digit is verified.
const DIGIT_LOOKALIKES: Record<string, string> = {
    O: '0',
    Q: '0',
    D: '0',
    I: '1',
    L: '1',
    Z: '2',
    S: '5',
    B: '8',
    G: '6'
}

function toDigits(value: string) {
    return [...value].map((character) => DIGIT_LOOKALIKES[character] ?? character).join('')
}

export function getMrzCheckDigit(value: string) {
    const weights = [7, 3, 1]
    let sum = 0

    for (let index = 0; index < value.length; index++) {
        const character = value[index]!
        let digit: number

        if (character >= '0' && character <= '9') {
            digit = character.charCodeAt(0) - 48
        } else if (character >= 'A' && character <= 'Z') {
            digit = character.charCodeAt(0) - 55
        } else {
            digit = 0
        }

        sum += digit * weights[index % 3]!
    }

    return String(sum % 10)
}

function normalizeLines(lines: string[]) {
    return lines
        .map((line) =>
            line
                .toUpperCase()
                .replace(/«/g, '<<')
                .replace(/‹/g, '<')
                .replace(/[^A-Z0-9<]/g, '')
        )
        .filter((line) => line.length > 0)
}

function hasLength(line: string, length: number) {
    return Math.abs(line.length - length) <= MRZ_LINE_LENGTH_TOLERANCE
}

function readField(line: string, start: number, length: number) {
    return line.slice(start, start + length)
}

interface IMrzFieldPositions {
    documentNumber: [line: number, start: number]
    dateOfBirth: [line: number, start: number]
    dateOfExpiry: [line: number, start: number]
}

const TD1_POSITIONS: IMrzFieldPositions = {
    documentNumber: [0, 5],
    dateOfBirth: [1, 0],
    dateOfExpiry: [1, 8]
}

const TD2_TD3_POSITIONS: IMrzFieldPositions = {
    documentNumber: [1, 0],
    dateOfBirth: [1, 13],
    dateOfExpiry: [1, 21]
}

function extract(lines: string[], positions: IMrzFieldPositions) {
    const [numberLine, numberStart] = positions.documentNumber
    const [birthLine, birthStart] = positions.dateOfBirth
    const [expiryLine, expiryStart] = positions.dateOfExpiry

    const documentNumber = readField(lines[numberLine]!, numberStart, 9)
    const dateOfBirth = toDigits(readField(lines[birthLine]!, birthStart, 6))
    const dateOfExpiry = toDigits(readField(lines[expiryLine]!, expiryStart, 6))

    if (!/[A-Z0-9]/.test(documentNumber) || !/^\d{6}$/.test(dateOfBirth) ||
        !/^\d{6}$/.test(dateOfExpiry)) return undefined

    const checks = [
        [documentNumber, readField(lines[numberLine]!, numberStart + 9, 1)],
        [dateOfBirth, readField(lines[birthLine]!, birthStart + 6, 1)],
        [dateOfExpiry, readField(lines[expiryLine]!, expiryStart + 6, 1)]
    ] as const

    for (const [value, expected] of checks) {
        // A document number longer than the field is continued in the optional data and its
        // check digit is '<'; that layout needs its own handling, so reject rather than guess.
        if (getMrzCheckDigit(value) !== toDigits(expected)) {
            return undefined
        }
    }

    return {
        documentNumber: documentNumber.replace(/<+$/, ''),
        dateOfBirth,
        dateOfExpiry
    } satisfies INfcIdReaderKey
}

function extractFromPairs(lines: string[], positions: IMrzFieldPositions) {
    for (let index = 0; index < lines.length - 1; index++) {
        if (!/^[PIAC]/.test(lines[index]!)) continue
        const key = extract([lines[index]!, lines[index + 1]!], positions)

        if (key) {
            return key
        }
    }

    return undefined
}

export function parseMrz(rawLines: string[]): INfcIdReaderKey | undefined {
    const lines = normalizeLines(rawLines)

    const td1 = lines.filter((line) => hasLength(line, MRZ_LINE_LENGTHS.TD1))
    const td1Key = extractFromPairs(td1, TD1_POSITIONS)

    if (td1Key) {
        return td1Key
    }

    for (const length of [MRZ_LINE_LENGTHS.TD3, MRZ_LINE_LENGTHS.TD2]) {
        const key = extractFromPairs(
            lines.filter((line) => hasLength(line, length)),
            TD2_TD3_POSITIONS
        )

        if (key) {
            return key
        }
    }

    return undefined
}
