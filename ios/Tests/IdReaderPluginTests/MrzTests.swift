import XCTest
@testable import IdReaderPlugin

final class MrzTests: XCTestCase {
    func testSharedFixtures() throws {
        let file = URL(fileURLWithPath: #filePath).deletingLastPathComponent().appendingPathComponent("mrz-fixtures.tsv")
        let contents = try String(contentsOf: file, encoding: .utf8)
        for row in contents.split(separator: "\n") {
            let fields = row.split(separator: "\t").map(String.init)
            XCTAssertEqual(MrzReader.read(Array(fields[2...3])) != nil, fields[1] == "valid", fields[0])
        }
    }

    func testPassportAccessData() {
        let lines = ["P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<", "L898902C36UTO7408122F1204159ZE184226B<<<<<10"]
        XCTAssertEqual(MrzReader.read(lines)?.key, "L898902C3740812120415")
    }

    func testCardAccessData() {
        let lines = ["I<UTOD231458907<<<<<<<<<<<<<<<", "7408122F1204159UTO<<<<<<<<<<<6"]
        XCTAssertEqual(MrzReader.read(lines)?.key, "D23145890740812120415")
    }

    func testRejectsNonNumericDatesAndEmptyNumbers() {
        let header = "P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<"
        let birth = "74A812"
        let invalid = "L898902C36UTO" + birth + NfcIdReaderPlugin.checkDigit(birth) + "F1204159ZE184226B<<<<<10"
        XCTAssertNil(MrzReader.read([header, invalid]))
        XCTAssertNil(MrzReader.read([header, "<<<<<<<<<0UTO7408122F1204159ZE184226B<<<<<10"]))
    }

    func testRejectsCorruptedAccessData() {
        let lines = ["P<UTOERIKSSON<<ANNA<MARIA<<<<<<<<<<<<<<<<<<<", "L899902C36UTO7408122F1204159ZE184226B<<<<<10"]
        XCTAssertNil(MrzReader.read(lines))
    }
}
