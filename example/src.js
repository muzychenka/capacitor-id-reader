import { NfcIdReader, parseMrz } from 'capacitor-id-reader'

const byId = (id) => document.getElementById(id)
const status = (message) => {
    byId('status').textContent = message
}
const fields = ['number', 'dob', 'expiry']
const clearResult = () => {
    byId('document-data').replaceChildren()
    byId('result').hidden = true
    byId('face-image').removeAttribute('src')
    byId('face-image').hidden = true
}
const showResult = (document) => {
    const labels = [
        ['Document type', 'documentCode'],
        ['Document number', 'documentNumber'],
        ['Issuing state', 'issuingState'],
        ['Nationality', 'nationality'],
        ['Surname', 'primaryIdentifier'],
        ['Given names', 'secondaryIdentifier'],
        ['Date of birth (YYMMDD)', 'dateOfBirth'],
        ['Expiry (YYMMDD)', 'dateOfExpiry'],
        ['Gender', 'gender'],
        ['Personal number', 'personalNumber']
    ]
    const list = byId('document-data')
    list.replaceChildren()
    for (const [label, key] of labels) {
        if (!document[key]) continue
        const term = window.document.createElement('dt')
        const value = window.document.createElement('dd')
        term.textContent = label
        value.textContent = document[key]
        list.append(term, value)
    }
    byId('result').hidden = false
}

byId('scan').onclick = async () => {
    try {
        clearResult()
        status('Opening MRZ camera…')
        const { lines } = await NfcIdReader.scanMrz()
        const key = parseMrz(lines)
        if (!key) {
            status('MRZ could not be read. Please try again.')
            return
        }
        byId('number').value = key.documentNumber
        byId('dob').value = key.dateOfBirth
        byId('expiry').value = key.dateOfExpiry
        status('MRZ access fields read. Hold the document against the NFC reader.')
    } catch (error) {
        status(`Scan failed: ${error.code ?? 'UNKNOWN'}`)
    }
}
byId('read').onclick = async () => {
    try {
        clearResult()
        const availability = await NfcIdReader.isAvailable()
        if (!availability.available || !availability.enabled) {
            status('NFC unavailable or disabled.')
            return
        }
        status('Hold the document against the phone and keep it still…')
        const result = await NfcIdReader.readDocument({
            documentNumber: byId('number').value.trim().toUpperCase(),
            dateOfBirth: byId('dob').value.trim(),
            dateOfExpiry: byId('expiry').value.trim(),
            dataGroups: byId('include-dg2').checked ? ['DG1', 'DG2'] : ['DG1']
        })
        showResult(result)
        if (result.faceImage) {
            const image = byId('face-image')
            image.onerror = () =>
                status(
                    `DG2 read; image format ${result.faceImage.mimeType} cannot be displayed on this device.`
                )
            image.src = result.faceImage.previewJpegBase64
                ? `data:image/jpeg;base64,${result.faceImage.previewJpegBase64}`
                : `data:${result.faceImage.mimeType};base64,${result.faceImage.base64}`
            image.hidden = false
        }
        status(
            `Read ${result.readDataGroups?.join(', ') ?? 'DG1'}. ${result.faceImage ? `Image: ${result.faceImage.mimeType}.` : 'No DG2 image requested.'} Authenticity verified: ${result.authenticityVerified}.`
        )
    } catch (error) {
        status(`NFC read failed: ${error.code ?? 'UNKNOWN'}`)
    }
}
byId('cancel').onclick = async () => {
    try {
        await NfcIdReader.cancel()
        status('Cancelled.')
    } catch (error) {
        status(`Cancel: ${error.code ?? 'UNKNOWN'}`)
    }
}
byId('clear').onclick = () => {
    for (const id of fields) byId(id).value = ''
    clearResult()
    status('Cleared.')
}
