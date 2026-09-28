import XCTest
@testable import WiglyMirror

final class CodecTests: XCTestCase {
    func testTouchLayoutMatchesCore() {
        let data = ControlCodec.touch(action: 1, pointer: 2, x: 0x0102, y: 0x0304, pressure: 0x0506)
        XCTAssertEqual(Array(data), [1, 1, 2, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06])
    }

    func testConfigIsFourteenBytes() {
        let data = ControlCodec.config(width: 1080, height: 2400, fps: 60, bitrate: 8_000_000, limit: 1080, codec: 1, flags: 1)
        XCTAssertEqual(data.count, 14)
        XCTAssertEqual(data[0], 7)
        XCTAssertEqual(data[5], 60)
        XCTAssertEqual(data[12], 1)
        XCTAssertEqual(data[13], 1)
    }

    func testRightClickIsBack() {
        let mapper = InputMapper(size: CGSize(width: 200, height: 400))
        let event = NSEvent.mouseEvent(
            with: .rightMouseDown, location: CGPoint(x: 10, y: 10),
            modifierFlags: [], timestamp: 0, windowNumber: 0, context: nil,
            eventNumber: 0, clickCount: 1, pressure: 1)!
        XCTAssertEqual(mapper.mouse(event: event), ControlCodec.button(ControlCodec.back))
    }

    func testAnnexBStartCodes() {
        var data = Data([0, 0, 0, 1, 0x40, 0x01, 0, 0, 0, 1, 0x42, 0x02])
        let nals = AnnexB.split(data)
        XCTAssertEqual(nals.count, 2)
        XCTAssertEqual(AnnexB.hevcType(nals[0]), 32)
        XCTAssertEqual(AnnexB.hevcType(nals[1]), 33)
        let prefixed = AnnexB.lengthPrefixed(nals)
        XCTAssertEqual(prefixed.count, 4 + 2 + 4 + 2)
        data.removeAll()
    }

    func testNormClamps() {
        XCTAssertEqual(ControlCodec.norm(0, span: 100), 0)
        XCTAssertEqual(ControlCodec.norm(100, span: 100), 65535)
        XCTAssertEqual(ControlCodec.norm(-4, span: 100), 0)
    }
}
