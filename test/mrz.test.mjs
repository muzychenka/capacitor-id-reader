import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseMrz, getMrzCheckDigit } from '../dist/parse-mrz.js'

const passport = [
    'P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<',
    'L898902C36UTO7408122F1204159ZE184226B<<<<<10'
]
const card = [
    'I<UTOD231458907<<<<<<<<<<<<<<<',
    '7408122F1204159UTO<<<<<<<<<<<6',
    'ERIKSSON<<ANNA<MARIA<<<<<<<<<<'
]

test('extracts checked access data from a TD3 passport', () => {
    assert.deepEqual(parseMrz(passport), {
        documentNumber: 'L898902C3',
        dateOfBirth: '740812',
        dateOfExpiry: '120415'
    })
})
test('extracts checked access data from a TD1 identity card', () => {
    assert.deepEqual(parseMrz(card), {
        documentNumber: 'D23145890',
        dateOfBirth: '740812',
        dateOfExpiry: '120415'
    })
})
test('extracts checked access data from a TD2 document', () => {
    const td2 = ['I<UTO' + 'X'.repeat(31), passport[1].slice(0, 36)]
    assert.deepEqual(parseMrz(td2), {
        documentNumber: 'L898902C3',
        dateOfBirth: '740812',
        dateOfExpiry: '120415'
    })
})
test('rejects incomplete or corrupted data', () => {
    assert.equal(parseMrz([passport[0]]), undefined)
    assert.equal(parseMrz([passport[0], passport[1].replace('L898', 'L899')]), undefined)
})
test('computes the ICAO check digit', () => assert.equal(getMrzCheckDigit('L898902C3'), '6'))

// Check digits alone cannot validate the type of a field.
test('rejects empty document numbers and nonnumeric access dates even with matching check digits', () => {
    const number = '<'.repeat(9)
    const line = number + '0UTO7408122F1204159' + '<'.repeat(16)
    assert.equal(parseMrz([passport[0], line]), undefined)
    const birth = '74A812'
    const invalid =
        passport[1].slice(0, 13) + birth + getMrzCheckDigit(birth) + passport[1].slice(20)
    assert.equal(parseMrz([passport[0], invalid]), undefined)
    assert.equal(parseMrz(['X'.repeat(44), passport[1]]), undefined)
})
